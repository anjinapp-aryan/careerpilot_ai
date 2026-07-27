package ai.careerpilot.ai.springai.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Phase 9.1 — the concrete (but unused) implementation of {@link ProviderAdapter},
 * backed by {@link SpringAiChatService}. This is the "Spring AI Adapter" side of the
 * {@code Current providers ↓ Spring AI Adapter} bridge described in
 * {@link ProviderAdapter}'s javadoc — it does NOT implement
 * {@link ai.careerpilot.ai.LlmProvider} and is not registered anywhere the AI
 * Gateway's provider chain could pick it up.
 *
 * <p>Gated identically to {@link SpringAiChatService} (which its constructor
 * depends on) — see that class's javadoc for why this annotation is load-bearing,
 * not decorative.
 */
@Component
@ConditionalOnProperty(prefix = "ai.springai.foundation", name = "enabled", havingValue = "true")
public class SpringAiProviderAdapter implements ProviderAdapter {

    private final SpringAiChatService chatService;

    public SpringAiProviderAdapter(SpringAiChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public String name() {
        return "spring-ai";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chatService.chat(systemPrompt, userMessage);
    }

    @Override
    public Flux<String> streamChat(String systemPrompt, String userMessage) {
        return chatService.streamChat(systemPrompt, userMessage);
    }
}
