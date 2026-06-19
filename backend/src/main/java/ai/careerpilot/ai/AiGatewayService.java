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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The single entry point for all AI in CareerPilot. Business services depend on
 * this — never on a concrete provider. Routes every call through the configured
 * provider order (Gemini → DeepSeek → Qwen) with automatic failover, per-provider
 * retry + circuit breaking (Resilience4j) + timeouts, usage metrics, and logging.
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
    private final Map<String, LlmProvider> providersByName = new LinkedHashMap<>();

    public AiGatewayService(List<LlmProvider> providers,
                            AiGatewayProperties props,
                            AiMetrics metrics,
                            RetryRegistry retryRegistry,
                            CircuitBreakerRegistry cbRegistry) {
        this.props = props;
        this.metrics = metrics;
        this.retryRegistry = retryRegistry;
        this.cbRegistry = cbRegistry;
        for (LlmProvider p : providers) {
            providersByName.put(p.name(), p);
        }
        log.info("AI Gateway initialized — order={}, configured={}",
                props.getOrder(), configuredKeys());
    }

    // ====================================================================
    // Public API — what business services call
    // ====================================================================

    public String chat(List<ChatMessage> messages, String system) {
        return execute("chat", p -> p.chat(messages, system, props.getDefaultTemperature()));
    }

    public Flux<String> streamChat(List<ChatMessage> messages, String system) {
        return streamFrom(orderedConfigured(), 0, messages, system, props.getDefaultTemperature());
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
            throw new AiGatewayException("No AI provider is configured", null);
        }
        Throwable last = null;
        for (int i = 0; i < chain.size(); i++) {
            LlmProvider p = chain.get(i);
            boolean hasNext = i < chain.size() - 1;
            CircuitBreaker cb = cbRegistry.circuitBreaker(p.name());

            if (cb.getState() == CircuitBreaker.State.OPEN) {
                log.warn("{} circuit is OPEN — skipping to fallback", p.displayName());
                if (hasNext) metrics.recordFallback();
                continue;
            }

            try {
                log.info("Job Match Request Received - Using {} Provider", p.displayName());
                metrics.recordCall(p.name());
                Supplier<T> decorated = Retry.decorateSupplier(retryRegistry.retry(p.name()),
                        CircuitBreaker.decorateSupplier(cb, () -> call.apply(p)));
                T result = decorated.get();
                log.info("Response Returned from {}", p.displayName());
                return result;
            } catch (CallNotPermittedException e) {
                last = e;
                log.warn("{} circuit opened mid-flight — skipping to fallback", p.displayName());
                if (hasNext) metrics.recordFallback();
            } catch (Exception e) {
                last = e;
                metrics.recordFailure(p.name());
                log.error("{} Provider failed: {} - {}", p.displayName(), e.getClass().getSimpleName(), e.getMessage(), e);
                if (hasNext) {
                    metrics.recordFallback();
                    log.warn("{} Failed → Switching to {}", p.displayName(), chain.get(i + 1).displayName());
                } else {
                    log.error("All {} providers exhausted for operation '{}'", chain.size(), op);
                }
            }
        }
        throw new AiGatewayException("All AI providers are unavailable", last);
    }

    // ====================================================================
    // Streaming failover core (fails over only before the first token)
    // ====================================================================

    private Flux<String> streamFrom(List<LlmProvider> chain, int idx,
                                    List<ChatMessage> messages, String system, double temperature) {
        if (chain.isEmpty()) {
            return Flux.error(new AiGatewayException("No AI provider is configured", null));
        }
        if (idx >= chain.size()) {
            return Flux.error(new AiGatewayException("All AI providers are unavailable", null));
        }
        LlmProvider p = chain.get(idx);
        boolean hasNext = idx < chain.size() - 1;
        CircuitBreaker cb = cbRegistry.circuitBreaker(p.name());

        if (!cb.tryAcquirePermission()) {
            log.warn("{} circuit is OPEN — skipping to fallback (stream)", p.displayName());
            if (hasNext) metrics.recordFallback();
            return streamFrom(chain, idx + 1, messages, system, temperature);
        }

        log.info("Copilot Request Received - Using {} Provider (stream)", p.displayName());
        metrics.recordCall(p.name());
        long start = System.nanoTime();
        AtomicBoolean emitted = new AtomicBoolean(false);

        return p.streamChat(messages, system, temperature)
                .doOnNext(t -> emitted.set(true))
                .doOnComplete(() -> {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    log.info("Response Returned from {} in {}ms", p.displayName(), elapsedMs);
                    cb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                })
                .onErrorResume(err -> {
                    cb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, err);
                    metrics.recordFailure(p.name());
                    log.error("{} Provider failed: {} - {}", p.displayName(), err.getClass().getSimpleName(), err.getMessage(), err);
                    if (emitted.get()) {
                        // Tokens already streamed to the client — cannot transparently fail over.
                        log.error("{} failed mid-stream — cannot failover", p.displayName());
                        return Flux.error(err);
                    }
                    if (hasNext) {
                        metrics.recordFallback();
                        log.warn("{} Failed → Switching to {}", p.displayName(), chain.get(idx + 1).displayName());
                    } else {
                        log.error("All {} providers exhausted", chain.size());
                    }
                    return streamFrom(chain, idx + 1, messages, system, temperature);
                });
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
