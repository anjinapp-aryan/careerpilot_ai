package ai.careerpilot.ai.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 9.3 — Spring AI Provider Migration flags. One flag per provider key, all
 * defaulting {@code false} (production keeps using the legacy provider
 * implementation until a flag is explicitly flipped). Read only by
 * {@link ProviderRegistryConfig} — {@link ai.careerpilot.ai.AiGatewayService} never
 * sees this class or knows a migration is happening.
 *
 * <pre>
 * spring-ai.providers.gemini.enabled      = false  (only one wired so far — see ProviderRegistryConfig)
 * spring-ai.providers.groq.enabled        = false  (flag exists, reserved — no Spring AI impl yet)
 * spring-ai.providers.sambanova.enabled   = false  (flag exists, reserved — no Spring AI impl yet)
 * spring-ai.providers.nvidia.enabled      = false  (flag exists, reserved — no Spring AI impl yet)
 * spring-ai.providers.qwen.enabled        = false  (flag exists, reserved — no Spring AI impl yet)
 * spring-ai.providers.openrouter.enabled  = false  (flag exists, reserved — no Spring AI impl yet)
 * </pre>
 *
 * Per the enterprise "migrate provider by provider" strategy (not a big-bang
 * migration), only {@code gemini} has a real {@code *SpringAiProvider}
 * implementation today. The other five flags are defined now so their config shape
 * is stable, but flipping one currently has zero effect — the legacy provider stays
 * the only implementation registered for that key until its own migration phase adds
 * the matching {@code @Bean} branch in {@link ProviderRegistryConfig}.
 */
@ConfigurationProperties(prefix = "spring-ai.providers")
public class SpringAiMigrationProperties {

    /** One flag per provider key; every flag defaults to disabled (legacy). */
    public static class Flag {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    private Flag gemini = new Flag();
    private Flag groq = new Flag();
    private Flag sambanova = new Flag();
    private Flag nvidia = new Flag();
    private Flag qwen = new Flag();
    private Flag openrouter = new Flag();

    public Flag getGemini() { return gemini; }
    public void setGemini(Flag gemini) { this.gemini = gemini; }

    public Flag getGroq() { return groq; }
    public void setGroq(Flag groq) { this.groq = groq; }

    public Flag getSambanova() { return sambanova; }
    public void setSambanova(Flag sambanova) { this.sambanova = sambanova; }

    public Flag getNvidia() { return nvidia; }
    public void setNvidia(Flag nvidia) { this.nvidia = nvidia; }

    public Flag getQwen() { return qwen; }
    public void setQwen(Flag qwen) { this.qwen = qwen; }

    public Flag getOpenrouter() { return openrouter; }
    public void setOpenrouter(Flag openrouter) { this.openrouter = openrouter; }
}
