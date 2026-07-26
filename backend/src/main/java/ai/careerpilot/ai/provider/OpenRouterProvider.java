package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayException;
import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.AiMetrics;
import ai.careerpilot.ai.Capability;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.ai.ModelPoolProvider;
import ai.careerpilot.ai.ProviderHealthTracker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The single provider bean for OpenRouter's entire model pool — last-resort fallback tier
 * (one slot in {@code ai.gateway.order}, reached once NVIDIA, Gemini, Groq, and SambaNova
 * have all failed). Unlike {@link NvidiaProvider}/{@link SambaNovaProvider} (one instance
 * per fixed model, each its own {@code ai.gateway.order} slot), OpenRouter is modeled as a
 * genuine <b>pool</b>: one bean that, per call, waterfalls through the {@link Capability}
 * -ranked candidate models from {@link OpenRouterModelRegistry} — config-driven, discovered
 * from OpenRouter's live catalog, never a hardcoded model name in Java.
 *
 * <p>Each candidate model gets its own Resilience4j retry/circuit-breaker instance (keyed
 * {@code "openrouter:<model-id>"}) and its own {@link AiMetrics}/{@link ProviderHealthTracker}
 * entries — reusing the exact same registries {@code AiGatewayService} already uses for the
 * outer chain, just one level deeper. This intentionally mirrors {@code
 * AiGatewayService.execute()}'s waterfall structure at a smaller scale; extracting a fully
 * shared "waterfall executor" would touch the core gateway class and was judged out of scope
 * for this pass (documented candidate for a future refactor).</p>
 */
@Component
public class OpenRouterProvider extends AbstractOpenAiChatProvider implements ModelPoolProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterProvider.class);
    public static final String KEY = "openrouter";

    private final AiGatewayProperties props;
    private final OpenRouterModelRegistry registry;
    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry cbRegistry;
    private final AiMetrics metrics;
    private final ProviderHealthTracker healthTracker;

    public OpenRouterProvider(AiGatewayProperties props, OpenRouterModelRegistry registry,
                               RetryRegistry retryRegistry, CircuitBreakerRegistry cbRegistry,
                               AiMetrics metrics, ProviderHealthTracker healthTracker) {
        super(props.provider(KEY));
        this.props = props;
        this.registry = registry;
        this.retryRegistry = retryRegistry;
        this.cbRegistry = cbRegistry;
        this.metrics = metrics;
        this.healthTracker = healthTracker;
    }

    @Override public String name() { return KEY; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "OpenRouter" : cfg.getDisplayName();
    }

    /**
     * Ignores the inherited "does {@code cfg.getModel()} look set" check (irrelevant here —
     * this provider has no single fixed model) and instead requires the cluster to be enabled,
     * an API key present, and at least one candidate model configured across all capabilities.
     */
    @Override public boolean isConfigured() {
        return props.getOpenRouter().isEnabled()
                && cfg.getApiKey() != null && !cfg.getApiKey().isBlank()
                && !registry.allConfiguredModelsDeduplicated().isEmpty();
    }

    @Override
    public String chat(List<ChatMessage> messages, String system, double temperature) {
        return chatByCapability(messages, system, temperature, null);
    }

    /**
     * Capability-aware entry point for future business-logic use (e.g. an agent that knows it
     * needs {@link Capability#CODING}). Purely additive — nothing in this codebase calls it
     * yet, so every existing call site (which only ever calls the no-capability {@link #chat})
     * is completely unaffected. {@code capability == null} falls back to the union of every
     * configured model across all capabilities, in configuration order.
     */
    public String chatByCapability(List<ChatMessage> messages, String system, double temperature, Capability capability) {
        List<String> candidates = candidatesFor(capability);
        if (candidates.isEmpty()) {
            throw new AiGatewayException("No OpenRouter model configured"
                    + (capability != null ? " for capability " + capability : ""), null, List.of());
        }
        Throwable last = null;
        for (String modelId : candidates) {
            String innerKey = innerKey(modelId);
            CircuitBreaker cb = cbRegistry.circuitBreaker(innerKey);
            if (cb.getState() == CircuitBreaker.State.OPEN) {
                log.debug("OPENROUTER_POOL model={} result=SKIPPED reason=CIRCUIT_OPEN", modelId);
                continue;
            }
            try {
                long start = System.nanoTime();
                Supplier<String> decorated = Retry.decorateSupplier(retryRegistry.retry(innerKey),
                        CircuitBreaker.decorateSupplier(cb, () -> chatWithModel(messages, system, temperature, modelId)));
                String result = decorated.get();
                if (result == null || result.isBlank()) {
                    last = new AiGatewayException(modelId + " returned an empty response", null);
                    recordFailure(innerKey, "empty response");
                    continue;
                }
                recordSuccess(innerKey, (System.nanoTime() - start) / 1_000_000);
                return result;
            } catch (Exception e) {
                last = e;
                recordFailure(innerKey, e.getMessage());
            }
        }
        throw new AiGatewayException("All OpenRouter pool models exhausted"
                + (capability != null ? " for capability " + capability : ""), last, candidates);
    }

    @Override
    public Flux<String> streamChat(List<ChatMessage> messages, String system, double temperature) {
        return streamByCapability(messages, system, temperature, null, 0);
    }

    /** Streaming counterpart of {@link #chatByCapability} — fails over only before the first token. */
    private Flux<String> streamByCapability(List<ChatMessage> messages, String system, double temperature,
                                             Capability capability, int fromIndex) {
        List<String> candidates = candidatesFor(capability);
        if (fromIndex >= candidates.size()) {
            return Flux.error(new AiGatewayException("All OpenRouter pool models exhausted"
                    + (capability != null ? " for capability " + capability : ""), null, candidates));
        }
        String modelId = candidates.get(fromIndex);
        String innerKey = innerKey(modelId);
        CircuitBreaker cb = cbRegistry.circuitBreaker(innerKey);
        if (!cb.tryAcquirePermission()) {
            return streamByCapability(messages, system, temperature, capability, fromIndex + 1);
        }
        long start = System.nanoTime();
        AtomicBoolean emitted = new AtomicBoolean(false);
        return streamChatWithModel(messages, system, temperature, modelId)
                .doOnNext(t -> emitted.set(true))
                .doOnComplete(() -> {
                    recordSuccess(innerKey, (System.nanoTime() - start) / 1_000_000);
                    cb.onSuccess(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
                })
                .onErrorResume(err -> {
                    cb.onError(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, err);
                    recordFailure(innerKey, err.getMessage());
                    if (emitted.get()) {
                        return Flux.error(err); // tokens already streamed — cannot fail over mid-stream
                    }
                    return streamByCapability(messages, system, temperature, capability, fromIndex + 1);
                });
    }

    @Override
    public List<Map<String, Object>> modelPoolStatuses() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String modelId : registry.allConfiguredModelsDeduplicated()) {
            String innerKey = innerKey(modelId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", modelId);
            m.put("inCatalog", registry.metadataFor(modelId).isPresent());
            m.put("circuitState", cbRegistry.circuitBreaker(innerKey).getState().name());
            m.put("health", healthTracker.getStatus(innerKey).name());
            m.put("avgLatencyMs", metrics.avgLatencyMs(innerKey));
            registry.metadataFor(modelId).ifPresent(meta -> {
                m.put("provider", meta.provider());
                m.put("contextWindow", meta.contextWindow());
                m.put("supportsToolCalling", meta.supportsToolCalling());
                m.put("supportsStructuredOutput", meta.supportsStructuredOutput());
                m.put("supportsVision", meta.supportsVision());
            });
            out.add(m);
        }
        return out;
    }

    // ---- helpers ----

    private List<String> candidatesFor(Capability capability) {
        return capability == null ? registry.allConfiguredModelsDeduplicated() : registry.candidatesFor(capability);
    }

    private static String innerKey(String modelId) {
        return "openrouter:" + modelId;
    }

    private void recordSuccess(String innerKey, long elapsedMs) {
        metrics.recordCall(innerKey);
        metrics.recordSuccess(innerKey);
        metrics.recordLatency(innerKey, elapsedMs);
        healthTracker.recordSuccess(innerKey);
    }

    private void recordFailure(String innerKey, String reason) {
        metrics.recordCall(innerKey);
        metrics.recordFailure(innerKey);
        healthTracker.recordFailure(innerKey, reason);
    }
}
