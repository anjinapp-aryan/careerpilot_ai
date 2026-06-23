package ai.careerpilot.ai;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The single entry point for all AI in CareerPilot. Business services depend on
 * this — never on a concrete provider. Routes every call through the configured
 * provider order (e.g. DeepSeek → Gemini → Groq → Qwen) with automatic failover,
 * per-provider retry + circuit breaking (Resilience4j) + timeouts, usage metrics,
 * and structured logging.
 *
 * Users never see internal provider failures: as long as one provider in the
 * chain succeeds, they get a response.
 */
@Service
public class AiGatewayService {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayService.class);

    private final AiGatewayProperties props;
    private final AiMetrics metrics;
    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry cbRegistry;
    private final ProviderHealthTracker healthTracker;
    private final Map<String, LlmProvider> providersByName = new LinkedHashMap<>();
    private final ThreadLocal<String> lastUsedProvider = new ThreadLocal<>();

    public AiGatewayService(List<LlmProvider> providers,
                            AiGatewayProperties props,
                            AiMetrics metrics,
                            RetryRegistry retryRegistry,
                            CircuitBreakerRegistry cbRegistry,
                            ProviderHealthTracker healthTracker) {
        this.props = props;
        this.metrics = metrics;
        this.retryRegistry = retryRegistry;
        this.cbRegistry = cbRegistry;
        this.healthTracker = healthTracker;
        for (LlmProvider p : providers) {
            providersByName.put(p.name(), p);
        }
        log.info("AI Gateway initialized — order={}, configured={}, primary={}",
                props.getOrder(), configuredKeys(), props.getPrimary());
    }

    // ====================================================================
    // Public API — what business services call
    // ====================================================================

    public String chat(List<ChatMessage> messages, String system) {
        return execute("chat", p -> p.chat(messages, system, props.getDefaultTemperature()));
    }

    public Flux<String> streamChat(List<ChatMessage> messages, String system) {
        return streamFrom(orderedConfigured(), 0, messages, system, props.getDefaultTemperature(), null);
    }

    public Flux<String> streamChat(List<ChatMessage> messages, String system, Consumer<String> providerCallback) {
        return streamFrom(orderedConfigured(), 0, messages, system, props.getDefaultTemperature(), providerCallback);
    }

    public String generateResumeFeedback(String resumeText) {
        return execute("resume_feedback", p -> p.generateResumeFeedback(resumeText));
    }

    public String generateCoverLetter(String resumeText, String jobDescription) {
        return execute("cover_letter", p -> p.generateCoverLetter(resumeText, jobDescription));
    }

    public String generateInterviewQuestions(String resumeText, String jobDescription) {
        return execute("interview_questions", p -> p.generateInterviewQuestions(resumeText, jobDescription));
    }

    public String generateAtsSuggestions(String resumeText, String jobDescription) {
        return execute("ats_suggestions", p -> p.generateAtsSuggestions(resumeText, jobDescription));
    }

    public String generateCareerAdvice(String context) {
        return execute("career_advice", p -> p.generateCareerAdvice(context));
    }

    public String generateJobMatchInsights(String resumeText, String jobDescription) {
        return execute("job_match", p -> p.generateJobMatchInsights(resumeText, jobDescription));
    }

    public String generateWorkflowRecommendations(String context) {
        return execute("workflow_recommendations", p -> p.generateWorkflowRecommendations(context));
    }

    // ====================================================================
    // Blocking failover core
    // ====================================================================

    private <T> T execute(String op, Function<LlmProvider, T> call) {
        List<LlmProvider> chain = orderedConfigured();
        if (chain.isEmpty()) {
            throw new AiGatewayException("No AI provider is configured", null, List.of());
        }
        Throwable last = null;
        for (int i = 0; i < chain.size(); i++) {
            LlmProvider p = chain.get(i);
            boolean hasNext = i < chain.size() - 1;
            CircuitBreaker cb = cbRegistry.circuitBreaker(p.name());

            if (cb.getState() == CircuitBreaker.State.OPEN) {
                metrics.recordCircuitOpen(p.name());
                log.warn("AI_GATEWAY provider={} result=SKIPPED reason=CIRCUIT_OPEN fallbackDepth={}", p.displayName(), i);
                if (hasNext) metrics.recordFallback();
                continue;
            }

            try {
                log.info("AI_GATEWAY op={} provider={} model={} attempt fallbackDepth={}", op, p.displayName(), modelOf(p), i);
                metrics.recordCall(p.name());
                long start = System.nanoTime();
                Supplier<T> decorated = Retry.decorateSupplier(retryRegistry.retry(p.name()),
                        CircuitBreaker.decorateSupplier(cb, () -> call.apply(p)));
                T result = decorated.get();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                // Treat a null/blank response as a failure and fail over — a 200 with no
                // usable content is no better than an exception for the caller.
                if (result instanceof String s && s.isBlank()) {
                    last = new AiGatewayException(p.displayName() + " returned an empty response", null);
                    metrics.recordFailure(p.name());
                    healthTracker.recordFailure(p.name(), "empty response");
                    log.warn("AI_GATEWAY provider={} result=FAILED reason=EMPTY_RESPONSE fallback={} fallbackDepth={}",
                            p.displayName(), hasNext ? chain.get(i + 1).displayName() : "NONE", i);
                    if (hasNext) metrics.recordFallback();
                    continue;
                }

                lastUsedProvider.set(p.name());
                metrics.recordSuccess(p.name());
                metrics.recordLatency(p.name(), elapsedMs);
                healthTracker.recordSuccess(p.name());
                log.info("AI_GATEWAY provider={} model={} result=SUCCESS latencyMs={} fallbackDepth={}",
                        p.displayName(), modelOf(p), elapsedMs, i);
                return result;
            } catch (CallNotPermittedException e) {
                last = e;
                metrics.recordCircuitOpen(p.name());
                log.warn("AI_GATEWAY provider={} result=FAILED reason=CIRCUIT_OPEN fallback={} fallbackDepth={}",
                        p.displayName(), hasNext ? chain.get(i + 1).displayName() : "NONE", i);
                if (hasNext) metrics.recordFallback();
            } catch (Exception e) {
                last = e;
                String reason = recordFailure(p, e);
                if (hasNext) {
                    metrics.recordFallback();
                    log.warn("AI_GATEWAY provider={} result=FAILED reason={} cause={} fallback={} fallbackDepth={}",
                            p.displayName(), reason, rootCause(e), chain.get(i + 1).displayName(), i);
                } else {
                    log.error("AI_GATEWAY provider={} result=FAILED reason={} cause={} fallback=NONE fallbackDepth={} — all {} providers exhausted for op='{}'",
                            p.displayName(), reason, rootCause(e), i, chain.size(), op, e);
                }
            }
        }
        throw new AiGatewayException("All AI providers are unavailable", last, displayNames(chain));
    }

    // ====================================================================
    // Failure classification + small helpers (shared by blocking + stream)
    // ====================================================================

    /** Record metrics + health for a failed call and return a short reason code for logging. */
    private String recordFailure(LlmProvider p, Throwable e) {
        metrics.recordFailure(p.name());
        if (isQuota(e)) {
            metrics.recordRateLimit(p.name());
            healthTracker.recordQuotaExceeded(p.name());
            return "RATE_LIMIT";
        }
        if (isTimeout(e)) {
            metrics.recordTimeout(p.name());
            healthTracker.recordFailure(p.name(), "timeout");
            return "TIMEOUT";
        }
        healthTracker.recordFailure(p.name(), e.getMessage());
        return "ERROR";
    }

    private static boolean isQuota(Throwable e) {
        return e instanceof QuotaExceededException
                || e.getCause() instanceof QuotaExceededException
                || (e.getMessage() != null && e.getMessage().contains("429"));
    }

    private static boolean isTimeout(Throwable e) {
        if (e instanceof java.util.concurrent.TimeoutException
                || e.getCause() instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        String m = e.getMessage();
        return m != null && m.toLowerCase().contains("timeout");
    }

    private String modelOf(LlmProvider p) {
        String model = props.provider(p.name()).getModel();
        return model == null ? "?" : model;
    }

    private static List<String> displayNames(List<LlmProvider> chain) {
        return chain.stream().map(LlmProvider::displayName).toList();
    }

    /** Short "ExceptionClass: message" of the deepest cause — enough to diagnose without a full stack. */
    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    // ====================================================================
    // Streaming failover core (fails over only before the first token)
    // ====================================================================

    private Flux<String> streamFrom(List<LlmProvider> chain, int idx,
                                    List<ChatMessage> messages, String system, double temperature,
                                    Consumer<String> providerCallback) {
        if (chain.isEmpty()) {
            return Flux.error(new AiGatewayException("No AI provider is configured", null, List.of()));
        }
        if (idx >= chain.size()) {
            return Flux.error(new AiGatewayException("All AI providers are unavailable", null, displayNames(chain)));
        }
        LlmProvider p = chain.get(idx);
        boolean hasNext = idx < chain.size() - 1;
        CircuitBreaker cb = cbRegistry.circuitBreaker(p.name());

        if (!cb.tryAcquirePermission()) {
            metrics.recordCircuitOpen(p.name());
            log.warn("AI_GATEWAY provider={} result=SKIPPED reason=CIRCUIT_OPEN fallbackDepth={} (stream)", p.displayName(), idx);
            if (hasNext) metrics.recordFallback();
            return streamFrom(chain, idx + 1, messages, system, temperature, providerCallback);
        }

        log.info("Copilot streaming request - Using {} Provider", p.displayName());
        metrics.recordCall(p.name());
        long start = System.nanoTime();
        AtomicBoolean emitted = new AtomicBoolean(false);

        return p.streamChat(messages, system, temperature)
                .doOnNext(t -> emitted.set(true))
                .doOnComplete(() -> {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    lastUsedProvider.set(p.name());
                    if (providerCallback != null) {
                        providerCallback.accept(p.name());
                    }
                    metrics.recordSuccess(p.name());
                    metrics.recordLatency(p.name(), elapsedMs);
                    healthTracker.recordSuccess(p.name());
                    log.info("AI_GATEWAY provider={} model={} result=SUCCESS latencyMs={} fallbackDepth={} (stream)",
                            p.displayName(), modelOf(p), elapsedMs, idx);
                    cb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                })
                .onErrorResume(err -> {
                    cb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, err);
                    String reason = recordFailure(p, err);
                    if (emitted.get()) {
                        // Tokens already streamed to the client — cannot transparently fail over.
                        log.error("AI_GATEWAY provider={} result=FAILED reason={} fallback=NONE_MID_STREAM fallbackDepth={} (stream)",
                                p.displayName(), reason, idx);
                        return Flux.error(err);
                    }
                    if (hasNext) {
                        metrics.recordFallback();
                        log.warn("AI_GATEWAY provider={} result=FAILED reason={} fallback={} fallbackDepth={} (stream)",
                                p.displayName(), reason, chain.get(idx + 1).displayName(), idx);
                    } else {
                        log.error("AI_GATEWAY provider={} result=FAILED reason={} fallback=NONE fallbackDepth={} — all {} providers exhausted (stream)",
                                p.displayName(), reason, idx, chain.size());
                    }
                    return streamFrom(chain, idx + 1, messages, system, temperature, providerCallback);
                });
    }

    public String getLastUsedProvider() {
        return lastUsedProvider.get();
    }

    public void clearLastUsedProvider() {
        lastUsedProvider.remove();
    }


    // ====================================================================
    // Health / stats support
    // ====================================================================

    /** Provider status keyed by name: UP | DOWN | NOT_CONFIGURED, plus "primary". */
    public Map<String, String> health() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : props.getOrder()) {
            LlmProvider p = providersByName.get(key);
            if (p == null || !p.isConfigured()) {
                out.put(key, "NOT_CONFIGURED");
            } else {
                CircuitBreaker.State state = cbRegistry.circuitBreaker(key).getState();
                out.put(key, state == CircuitBreaker.State.OPEN ? "DOWN" : "UP");
            }
        }
        out.put("primary", props.getPrimary());
        return out;
    }

    public Map<String, Object> stats() {
        return metrics.snapshot(props.getOrder());
    }

    /**
     * Per-provider status in failover order: name, displayName, configured, model,
     * circuit state, and last-known health. Read-only — backs {@code GET /api/ai/providers}.
     */
    public List<Map<String, Object>> providerStatuses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : props.getOrder()) {
            LlmProvider p = providersByName.get(key);
            boolean configured = p != null && p.isConfigured();
            CircuitBreaker.State cbState = cbRegistry.circuitBreaker(key).getState();
            String status = !configured
                    ? "NOT_CONFIGURED"
                    : (cbState == CircuitBreaker.State.OPEN ? "DOWN" : "UP");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", key);
            m.put("displayName", p != null ? p.displayName() : key);
            m.put("configured", configured);
            m.put("model", props.provider(key).getModel());
            m.put("status", status);
            m.put("circuitState", cbState.name());
            m.put("health", healthTracker.getStatus(key).name());
            out.add(m);
        }
        return out;
    }

    /**
     * Router configuration snapshot — backs {@code GET /api/ai/router/status}.
     * In Phase A {@code mode} is always "sequential" unless the smart-router flag
     * is enabled; the routing map is reported for visibility but not yet consulted.
     */
    public Map<String, Object> routerStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("smartRouterEnabled", props.isSmartRouterEnabled());
        m.put("mode", props.isSmartRouterEnabled() ? "task-aware" : "sequential");
        m.put("order", props.getOrder());
        m.put("primary", props.getPrimary());
        m.put("routing", props.getRouting());
        return m;
    }

    // ====================================================================
    // Internals
    // ====================================================================

    private List<LlmProvider> orderedConfigured() {
        List<LlmProvider> chain = new ArrayList<>();
        for (String key : props.getOrder()) {
            LlmProvider p = providersByName.get(key);
            if (p != null && p.isConfigured()) {
                chain.add(p);
            }
        }
        return chain;
    }

    private List<String> configuredKeys() {
        return orderedConfigured().stream().map(LlmProvider::name).toList();
    }
}
