package ai.careerpilot.ai.springai.adapter;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Phase 9.1 — thin wrapper around the Spring AI {@link ChatModel}/{@link
 * StreamingChatModel} beans (see {@code SpringAiConfig}). Not injected by any
 * controller or existing business service — {@code CopilotService} and
 * {@code WorkflowService} continue to depend solely on
 * {@link ai.careerpilot.ai.AiGatewayService}, unchanged. This class exists as the
 * shape a future migration phase would build on, not as a currently-reachable code
 * path.
 *
 * <p>Explicitly gated with the same {@code @ConditionalOnProperty} as {@code
 * SpringAiConfig} — {@code @Service}/{@code @Component} classes are otherwise
 * component-scanned and instantiated unconditionally regardless of what beans their
 * constructor asks for, so without this the app would fail to start whenever the
 * flag is off and {@code ChatModel} doesn't exist (caught in local verification
 * before this reached any deployed environment).
 */
@Service
@ConditionalOnProperty(prefix = "ai.springai.foundation", name = "enabled", havingValue = "true")
public class SpringAiChatService {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;

    public SpringAiChatService(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    public String chat(String systemPrompt, String userMessage) {
        Prompt prompt = new Prompt(toMessages(systemPrompt, userMessage));
        return chatModel.call(prompt).getResult().getOutput().getText();
    }

    public Flux<String> streamChat(String systemPrompt, String userMessage) {
        Prompt prompt = new Prompt(toMessages(systemPrompt, userMessage));
        return streamingChatModel.stream(prompt)
                .map(response -> response.getResult().getOutput().getText());
    }

    private static List<Message> toMessages(String systemPrompt, String userMessage) {
        return List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage));
    }
}
