package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.springframework.stereotype.Component;

/**
 * Priority-2 provider: NVIDIA-hosted DeepSeek (OpenAI-compatible Chat Completions).
 */
@Component
public class NvidiaDeepSeekProvider extends AbstractOpenAiChatProvider {

    public static final String KEY = "deepseek";

    public NvidiaDeepSeekProvider(AiGatewayProperties props) {
        super(props.provider(KEY));
    }

    @Override public String name() { return KEY; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "DeepSeek" : cfg.getDisplayName();
    }
}
