package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.springframework.stereotype.Component;

/**
 * Priority-3 provider: NVIDIA-hosted Qwen3 Next 80B (OpenAI-compatible Chat Completions).
 */
@Component
public class NvidiaQwenProvider extends AbstractOpenAiChatProvider {

    public static final String KEY = "qwen";

    public NvidiaQwenProvider(AiGatewayProperties props) {
        super(props.provider(KEY));
    }

    @Override public String name() { return KEY; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "Qwen" : cfg.getDisplayName();
    }
}
