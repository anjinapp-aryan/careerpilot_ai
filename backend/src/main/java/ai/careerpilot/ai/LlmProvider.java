package ai.careerpilot.ai;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * Contract every LLM provider implements. Business code never depends on this
 * directly — it goes through {@code AiGatewayService}, which routes across
 * providers with automatic failover. Adding a new provider (Claude, OpenAI,
 * Grok, OpenRouter, …) means writing one new implementation and listing it in
 * {@code ai.gateway.order} — no business-logic changes.
 */
public interface LlmProvider {

    /** Stable key used in config/metrics/circuit-breaker names (e.g. "gemini"). */
    String name();

    /** Human-friendly name used in logs (e.g. "Gemini"). */
    String displayName();

    /** True when this provider has the credentials it needs to be called. */
    boolean isConfigured();

    /** Per-provider request timeout. */
    Duration timeout();

    // ---- Core primitives ----

    /** Multi-turn, non-streaming completion. */
    String chat(List<ChatMessage> messages, String system, double temperature);

    /** Multi-turn, streaming completion (ChatGPT-style token deltas). */
    Flux<String> streamChat(List<ChatMessage> messages, String system, double temperature);

    // ---- Feature helpers (implemented once in AbstractLlmProvider) ----

    String generateResumeFeedback(String resumeText);

    String generateCoverLetter(String resumeText, String jobDescription);

    String generateInterviewQuestions(String resumeText, String jobDescription);

    String generateAtsSuggestions(String resumeText, String jobDescription);

    String generateCareerAdvice(String context);

    String generateJobMatchInsights(String resumeText, String jobDescription);

    String generateWorkflowRecommendations(String context);
}
