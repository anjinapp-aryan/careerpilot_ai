package ai.careerpilot.ai.springai.adapter;

import reactor.core.publisher.Flux;

/**
 * Phase 9.1 — the future bridge contract: {@code Current providers ↓ Spring AI
 * Adapter}. Deliberately a separate, minimal type — not
 * {@link ai.careerpilot.ai.LlmProvider} itself — so this package has zero coupling
 * to the existing provider hierarchy and cannot accidentally get picked up by
 * anything that autowires {@code List<LlmProvider>} (see
 * {@code AiGatewayService}'s constructor). Not implemented by anything wired into
 * production; see {@link SpringAiProviderAdapter} for the one (unused) implementation
 * that exists today.
 */
public interface ProviderAdapter {

    /** Stable identifier, mirroring the provider-key convention used by {@code ai.gateway.order}. */
    String name();

    /** Whether this adapter is configured well enough to be called (analogous to {@code LlmProvider.isConfigured()}). */
    boolean isAvailable();

    /** Blocking chat call. */
    String chat(String systemPrompt, String userMessage);

    /** Streaming chat call. */
    Flux<String> streamChat(String systemPrompt, String userMessage);
}
