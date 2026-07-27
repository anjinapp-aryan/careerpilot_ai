package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AbstractLlmProvider;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.ai.springai.SpringAiFoundationProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 9.2 — the first real {@link ai.careerpilot.ai.LlmProvider} backed by the
 * Spring AI foundation from Phase 9.1 ({@code ai.careerpilot.ai.springai}). This is
 * a genuine, callable provider — unlike everything built in 9.1 — but it is added to
 * {@code ai.gateway.order} in the canary (last) position, so it is only ever reached
 * after every existing provider has failed.
 *
 * <p>Registered as a plain, unconditional {@code @Component} deliberately — NOT
 * behind {@code @ConditionalOnProperty} — because {@link ai.careerpilot.ai.AiGatewayService}
 * autowires {@code List<LlmProvider>}, and every existing provider in this codebase
 * (NVIDIA/Gemini/Groq/SambaNova/OpenRouter) follows the same "always registered,
 * {@link #isConfigured()} gates real use" pattern rather than being conditionally
 * absent from the bean graph. The {@link ChatModel}/{@link StreamingChatModel} beans
 * this class depends on DO still only exist when {@code ai.springai.foundation.enabled=true}
 * (see {@code SpringAiConfig}), so they're injected via {@link ObjectProvider} —
 * {@link #isConfigured()} returns {@code false} whenever they're absent, which is the
 * default (flag off) — this provider is then automatically skipped by the gateway,
 * exactly like an NVIDIA provider with no API key.
 */
@Component
public class SpringAiLlmProvider extends AbstractLlmProvider {

    /** Matches the convention of every other provider key in {@code ai.gateway.order}. */
    public static final String KEY = "spring_ai";

    private final SpringAiFoundationProperties props;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;

    public SpringAiLlmProvider(SpringAiFoundationProperties props,
                                ObjectProvider<ChatModel> chatModelProvider,
                                ObjectProvider<StreamingChatModel> streamingChatModelProvider) {
        this.props = props;
        this.chatModel = chatModelProvider.getIfAvailable();
        this.streamingChatModel = streamingChatModelProvider.getIfAvailable();
    }

    @Override
    public String name() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "Spring AI (canary)";
    }

    @Override
    public boolean isConfigured() {
        return props.isEnabled()
                && chatModel != null
                && streamingChatModel != null
                && props.getApiKey() != null
                && !props.getApiKey().isBlank();
    }

    @Override
    public Duration timeout() {
        return Duration.ofMillis(props.getTimeoutMs());
    }

    @Override
    public String chat(List<ChatMessage> messages, String system, double temperature) {
        Prompt prompt = toPrompt(messages, system, temperature);
        return chatModel.call(prompt).getResult().getOutput().getText();
    }

    @Override
    public Flux<String> streamChat(List<ChatMessage> messages, String system, double temperature) {
        Prompt prompt = toPrompt(messages, system, temperature);
        return streamingChatModel.stream(prompt)
                .map(response -> response.getResult().getOutput().getText());
    }

    private Prompt toPrompt(List<ChatMessage> messages, String system, double temperature) {
        List<Message> out = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            out.add(new SystemMessage(system));
        }
        for (ChatMessage m : messages) {
            // ChatMessage.role() uses Gemini's vocabulary ("user"/"model") — see
            // ChatMessage's own javadoc; "model" is the assistant turn.
            out.add("model".equals(m.role()) ? new AssistantMessage(m.content()) : new UserMessage(m.content()));
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(props.getChatModel())
                .temperature(temperature)
                .build();
        return new Prompt(out, options);
    }
}
