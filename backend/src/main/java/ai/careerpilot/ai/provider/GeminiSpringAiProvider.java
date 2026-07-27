package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AbstractLlmProvider;
import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.ChatMessage;
import com.google.genai.Client;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 9.3 — Spring-AI-backed replacement for {@link GeminiProvider}, migration
 * item 1 of 6 (lowest risk, per the enterprise migration order). Same protocol
 * target (Gemini Developer API, api-key auth — NOT Vertex AI, which needs a GCP
 * service account this deployment doesn't have) and the SAME config source
 * ({@code ai.gateway.providers.gemini.*}, identical to {@link GeminiProvider}) — only
 * the HTTP/SDK layer changes, from this codebase's own {@code WebClient}-based REST
 * calls to Spring AI's {@code GoogleGenAiChatModel} (backed by Google's official
 * {@code com.google.genai.Client}). No new credentials, no new env vars.
 *
 * <p>Selected instead of {@link GeminiProvider} only when {@code
 * spring-ai.providers.gemini.enabled=true} — see {@link
 * ai.careerpilot.ai.migration.ProviderRegistryConfig}, the only place that decides
 * between the two. Returns {@code name()=="gemini"}, identical to the legacy
 * provider, so it reuses the exact same {@code ai.gateway.order} position,
 * Resilience4j retry/circuit-breaker instances, and health/metrics keying —
 * genuinely a drop-in engine swap, not a new provider slot.
 *
 * <p>{@link #displayName()} deliberately differs from the legacy provider's
 * ("Gemini (Spring AI)" vs "Gemini") — this is the observability seam: every
 * existing log line, metric, and diagnostics entry that already reports {@code
 * displayName()} (see {@code AiGatewayService}) now shows which execution engine
 * served a given call, with zero new plumbing.
 */
public class GeminiSpringAiProvider extends AbstractLlmProvider {

    public static final String KEY = "gemini";

    private final AiGatewayProperties.Provider cfg;
    private final ChatModel chatModel;

    public GeminiSpringAiProvider(AiGatewayProperties props) {
        this.cfg = props.provider(KEY);
        Client client = Client.builder()
                .apiKey(cfg.getApiKey() == null ? "" : cfg.getApiKey())
                .vertexAI(false)
                .build();
        this.chatModel = GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(resolveModel(cfg.getModel())).build())
                .build();
    }

    @Override
    public String name() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "Gemini (Spring AI)";
    }

    @Override
    public boolean isConfigured() {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    @Override
    public Duration timeout() {
        return Duration.ofMillis(cfg.getTimeoutMs());
    }

    @Override
    public String chat(List<ChatMessage> messages, String system, double temperature) {
        Prompt prompt = toPrompt(messages, system, temperature);
        return chatModel.call(prompt).getResult().getOutput().getText();
    }

    @Override
    public Flux<String> streamChat(List<ChatMessage> messages, String system, double temperature) {
        Prompt prompt = toPrompt(messages, system, temperature);
        return chatModel.stream(prompt)
                .map(response -> response.getResult().getOutput().getText());
    }

    private Prompt toPrompt(List<ChatMessage> messages, String system, double temperature) {
        List<Message> out = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            out.add(new SystemMessage(system));
        }
        for (ChatMessage m : messages) {
            // ChatMessage.role() uses Gemini's own vocabulary ("user"/"model") — see
            // ChatMessage's javadoc; "model" is the assistant turn.
            out.add("model".equals(m.role()) ? new AssistantMessage(m.content()) : new UserMessage(m.content()));
        }
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(resolveModel(cfg.getModel()))
                .temperature(temperature)
                .build();
        return new Prompt(out, options);
    }

    /**
     * {@code ai.gateway.providers.gemini.model} is a plain string (e.g.
     * "gemini-2.5-flash", set once for both the legacy and this provider) but Spring
     * AI's Google GenAI integration requires its own enum type. Matches by value
     * rather than hardcoding a static map, so a future model-string config change
     * doesn't need a matching Java change too — falls back to GEMINI_2_5_FLASH
     * (this deployment's actual configured default) only if the configured string
     * doesn't match any known enum value, rather than silently guessing.
     */
    private static GoogleGenAiChatModel.ChatModel resolveModel(String configuredModel) {
        if (configuredModel != null) {
            for (GoogleGenAiChatModel.ChatModel candidate : GoogleGenAiChatModel.ChatModel.values()) {
                if (candidate.getValue().equals(configuredModel)) {
                    return candidate;
                }
            }
        }
        return GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH;
    }
}
