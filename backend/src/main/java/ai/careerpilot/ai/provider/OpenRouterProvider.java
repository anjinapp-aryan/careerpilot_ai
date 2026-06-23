package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.springframework.stereotype.Component;

/**
 * Priority-5 (last-resort) provider: OpenRouter (OpenAI-compatible Chat Completions).
 *
 * OpenRouter's API speaks the same {@code /chat/completions} protocol as NVIDIA NIM,
 * Groq, and other OpenAI-compatible providers, so this is a thin subclass of
 * {@link AbstractOpenAiChatProvider} with no transport code of its own — identical to
 * {@link GroqProvider} and {@link NvidiaDeepSeekProvider}.
 *
 * <p>It is appended to the failover chain <em>after</em> Qwen
 * (DeepSeek → Gemini → Groq → Qwen → OpenRouter) and joins the chain only when both
 * {@code OPENROUTER_API_KEY} and a model are set. Without {@code OPENROUTER_API_KEY},
 * {@link #isConfigured()} is false and the gateway silently skips it, so the live
 * production order is unchanged until a key is supplied.</p>
 *
 * <p>OpenRouter provides free and paid model access through a unified API; the model
 * can be configured via {@code OPENROUTER_MODEL} (e.g., {@code qwen/qwen3-next-80b-a3b-instruct:free}
 * for free tier, or any available model for paid accounts).</p>
 */
@Component
public class OpenRouterProvider extends AbstractOpenAiChatProvider {

    public static final String KEY = "openrouter";

    public OpenRouterProvider(AiGatewayProperties props) {
        super(props.provider(KEY));
    }

    @Override public String name() { return KEY; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "OpenRouter" : cfg.getDisplayName();
    }
}
