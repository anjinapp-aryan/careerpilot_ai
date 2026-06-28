package ai.careerpilot.ai.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Single seam for text embeddings — business code (job/resume embedding, semantic search) depends on
 * this, never on a concrete {@link EmbeddingProvider}. Owns the feature flag, source-text truncation,
 * failure isolation, and conversion to the pgvector literal used in native SQL.
 *
 * <p>Gated by {@code ai.embeddings.enabled} (default false): when off, {@link #isEnabled()} is false
 * and {@link #embed} returns empty, so callers no-op and the feature ships dark.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProvider provider;
    private final boolean enabled;
    private final int maxChars;

    public EmbeddingService(List<EmbeddingProvider> providers,
                            @Value("${ai.embeddings.enabled:false}") boolean enabled,
                            @Value("${ai.embeddings.max-chars:8000}") int maxChars) {
        // First configured provider wins (only Gemini exists today); null when none is usable.
        this.provider = providers.stream().filter(EmbeddingProvider::isConfigured).findFirst().orElse(null);
        this.enabled = enabled;
        this.maxChars = maxChars;
        log.info("EmbeddingService — enabled={}, provider={}, dimensions={}",
                enabled, provider == null ? "NONE" : provider.name(),
                provider == null ? 0 : provider.dimensions());
    }

    /** Live only when the flag is on AND a configured provider exists. */
    public boolean isEnabled() {
        return enabled && provider != null;
    }

    public int dimensions() {
        return provider == null ? 0 : provider.dimensions();
    }

    /**
     * Embed text, or empty when disabled, the text is blank, or the provider fails. Never throws —
     * embedding is best-effort and must never break the calling flow (ingest, upload, search).
     */
    public Optional<float[]> embed(String text) {
        if (!isEnabled() || text == null || text.isBlank()) return Optional.empty();
        String trimmed = text.length() > maxChars ? text.substring(0, maxChars) : text;
        try {
            float[] vec = provider.embed(trimmed);
            return (vec == null || vec.length == 0) ? Optional.empty() : Optional.of(vec);
        } catch (Exception e) {
            log.warn("Embedding failed (provider={}): {}", provider.name(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Render a vector as a pgvector literal, e.g. {@code [0.0123,-0.0456,...]}, for native
     * {@code CAST(:vec AS vector)} binding. Compact form keeps the SQL payload small.
     */
    public static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
