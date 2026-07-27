package ai.careerpilot.ai.springai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Phase 9.1 — Spring AI Foundation configuration.
 *
 * <p><b>Not wired into production.</b> Every bean here exists purely so the types are
 * available for a future migration phase; nothing in {@code ai.careerpilot} outside
 * this package (and its {@code adapter} sub-package) references {@link ChatModel},
 * {@link StreamingChatModel}, or {@link EmbeddingModel}. The existing
 * {@link ai.careerpilot.ai.AiGatewayService} and its provider chain are completely
 * untouched by this class.
 *
 * <p>Gated by {@code ai.springai.foundation.enabled} (default {@code false} — see
 * {@link SpringAiFoundationProperties}). When disabled, {@code @ConditionalOnProperty}
 * means Spring never even invokes these {@code @Bean} methods, so there is zero
 * startup cost, zero network client construction, and zero risk when the flag is off
 * — which it is, everywhere, until a future phase explicitly turns it on.
 *
 * <p>This is a deliberately separate {@link OpenAIClient} instance from any existing
 * provider (e.g. {@code NvidiaProvider}) — no shared state, no shared connection
 * pool, no behavioral coupling in either direction.
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.springai.foundation", name = "enabled", havingValue = "true")
public class SpringAiConfig {

    private final SpringAiFoundationProperties props;

    public SpringAiConfig(SpringAiFoundationProperties props) {
        this.props = props;
    }

    /**
     * The shared OpenAI-protocol client backing both the chat and embedding beans
     * below. Construction alone makes no network call — the OpenAI Java SDK builds
     * this lazily; a real request only happens if something actually calls
     * {@link ChatModel#call} or {@link EmbeddingModel#call}, which nothing does yet.
     *
     * <p>The OpenAI SDK's {@code ClientOptions.Builder} throws eagerly, at
     * construction time, if given a blank api-key ("At least one credential source
     * must be specified" — caught in local verification). Every other provider in
     * this codebase ({@code AbstractOpenAiChatProvider}, used by NVIDIA/Groq/
     * SambaNova/OpenRouter) tolerates a blank key at construction and only fails
     * gracefully via {@code isConfigured()} — falling back to a non-blank placeholder
     * here preserves that same resilience: someone flipping {@code
     * ai.springai.foundation.enabled=true} without also setting a real api-key gets a
     * provider that reports itself unconfigured (see {@code SpringAiLlmProvider}),
     * not a crash-looping app.
     */
    @Bean
    public OpenAIClient springAiOpenAiClient() {
        String apiKey = props.getApiKey() == null || props.getApiKey().isBlank()
                ? "unset" : props.getApiKey();
        return OpenAIOkHttpClient.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(apiKey)
                .build();
    }

    /**
     * Foundation {@link ChatModel} bean. Not injected by any controller or business
     * service today — see the class javadoc.
     */
    @Bean
    public ChatModel springAiChatModel(OpenAIClient springAiOpenAiClient) {
        // OpenAiChatModel.Builder needs BOTH a sync and an async OpenAI client — blocking
        // call() uses the sync one, streaming stream() uses the async one. Passing only
        // openAiClient(...) leaves Spring AI to construct the async client from scratch
        // internally, which does NOT inherit this bean's api-key/base-url and fails with
        // "At least one credential source must be specified" (caught in local
        // verification — see the OpenAIClient.async() derivation below, which correctly
        // shares this client's already-configured credentials instead).
        return OpenAiChatModel.builder()
                .openAiClient(springAiOpenAiClient)
                .openAiClientAsync(springAiOpenAiClient.async())
                .options(OpenAiChatOptions.builder().model(props.getChatModel()).build())
                .build();
    }

    /**
     * {@link ChatModel} already extends {@link StreamingChatModel} in Spring AI
     * 2.0.0 (see {@code org.springframework.ai.chat.model.ChatModel}'s own type
     * hierarchy) — this bean exposes that same instance under its narrower,
     * streaming-only type so future code can depend on just the streaming contract
     * without pulling in blocking {@code call()} methods it doesn't need. It is the
     * same object as {@link #springAiChatModel}, not a second client.
     *
     * <p>{@code @Primary} is required, not decorative: because {@link ChatModel}
     * itself satisfies {@link StreamingChatModel}, Spring sees two candidates for any
     * {@code StreamingChatModel}-typed injection point ({@code springAiChatModel} and
     * this bean) and refuses to start without a disambiguator (caught in local
     * verification).
     */
    @Bean
    @Primary
    public StreamingChatModel springAiStreamingChatModel(ChatModel springAiChatModel) {
        return springAiChatModel;
    }

    /**
     * Foundation {@link EmbeddingModel} bean. {@code pgvector} columns already exist
     * on {@code resumes.embedding}/{@code jobs.embedding} (see CLAUDE.md,
     * "Provisioned-but-unused") but no code path generates embeddings today — this
     * bean does not change that; it is not injected anywhere.
     */
    @Bean
    public EmbeddingModel springAiEmbeddingModel(OpenAIClient springAiOpenAiClient) {
        return OpenAiEmbeddingModel.builder()
                .openAiClient(springAiOpenAiClient)
                .options(OpenAiEmbeddingOptions.builder().model(props.getEmbeddingModel()).build())
                .build();
    }
}
