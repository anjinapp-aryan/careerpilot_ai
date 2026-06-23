package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.springframework.stereotype.Component;

/**
 * Fallback provider: Groq-hosted Llama 3.3 70B (OpenAI-compatible Chat Completions).
 *
 * Groq's API speaks the same {@code /chat/completions} protocol as NVIDIA NIM, so
 * this is a thin subclass of {@link AbstractOpenAiChatProvider} — no transport code
 * of its own. It only joins the failover chain when {@code GROQ_API_KEY} is set
 * (otherwise {@link #isConfigured()} is false and the gateway skips it), exactly
 * like the NVIDIA providers behave without their own dedicated key
 * ({@code DEEP_SHEEK_NVIDIA_API_KEY} / {@code QWEN3_NVIDIA_API_KEY}).
 */
@Component
public class GroqProvider extends AbstractOpenAiChatProvider {

    public static final String KEY = "groq";

    public GroqProvider(AiGatewayProperties props) {
        super(props.provider(KEY));
    }

    @Override public String name() { return KEY; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "Groq" : cfg.getDisplayName();
    }
}
