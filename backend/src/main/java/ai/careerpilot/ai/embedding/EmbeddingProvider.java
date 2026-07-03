package ai.careerpilot.ai.embedding;

/**
 * Adapter contract for a text-embedding backend, mirroring the {@code LlmProvider} convention: an
 * implementation joins the active set only when {@link #isConfigured()} is true. Today the only
 * implementation is Gemini ({@code text-embedding-004}); adding another (OpenAI, Cohere, a local
 * model) is a new bean implementing this interface — {@link EmbeddingService} picks the configured
 * one with no business-logic change.
 */
public interface EmbeddingProvider {

    /** Stable lowercase provider name (e.g. "gemini"). */
    String name();

    /** Whether this provider is usable in the current configuration (API key present, etc.). */
    boolean isConfigured();

    /** Vector dimensionality this provider produces (e.g. 768) — must match the DB vector column. */
    int dimensions();

    /**
     * Embed a single text into a dense vector. Implementations must not retry aggressively or block
     * indefinitely; they may throw on failure (the caller isolates and logs). Never returns null —
     * throws instead, so an all-zero vector is never silently persisted.
     */
    float[] embed(String text);
}
