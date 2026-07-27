package ai.careerpilot.ai.springai.adapter;

import ai.careerpilot.ai.Capability;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Phase 9.1 — foundation-only factory. Today there is exactly one configured
 * {@link ChatModel}/{@link EmbeddingModel} pair (see {@code SpringAiConfig}), so this
 * class does no real selection yet — it exists to give a future phase a single seam
 * to extend into real capability-based model selection (mirroring the pattern already
 * proven in {@link ai.careerpilot.ai.provider.OpenRouterProvider}'s internal model
 * pool) without every caller needing to change. Not injected anywhere outside this
 * package.
 *
 * <p>Gated identically to {@link SpringAiChatService} — see that class's javadoc.
 */
@Component
@ConditionalOnProperty(prefix = "ai.springai.foundation", name = "enabled", havingValue = "true")
public class SpringAiModelFactory {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    public SpringAiModelFactory(ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Returns the configured {@link ChatModel} for a capability, if one is available.
     * Currently always the single foundation model regardless of capability — real
     * per-capability model selection is future scope, not implemented here.
     */
    public Optional<ChatModel> chatModelFor(Capability capability) {
        return Optional.of(chatModel);
    }

    public Optional<EmbeddingModel> embeddingModelFor(Capability capability) {
        return capability == Capability.EMBEDDING ? Optional.of(embeddingModel) : Optional.empty();
    }
}
