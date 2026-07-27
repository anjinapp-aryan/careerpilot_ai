package ai.careerpilot.ai.migration;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.LlmProvider;
import ai.careerpilot.ai.provider.GeminiProvider;
import ai.careerpilot.ai.provider.GeminiSpringAiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 9.3 — the single place that decides, per provider key, whether the legacy
 * or the Spring-AI-backed implementation is the one actually registered as an
 * {@link LlmProvider} bean. {@code AiGatewayService} still autowires {@code
 * List<LlmProvider>} exactly as before (unchanged file, unchanged constructor) — it
 * has no idea this class exists. Because each {@code @Bean} method below returns
 * exactly ONE {@link LlmProvider} per key (never both), there is no ambiguity in
 * that autowiring: the resolved list looks identical in shape whether every flag is
 * off (today) or every flag is on.
 *
 * <p>Only {@code gemini} has a real second implementation so far — see {@link
 * SpringAiMigrationProperties}'s javadoc for why the other five flags exist but are
 * currently inert. Migrating the next provider means adding one more {@code @Bean}
 * method here, following the exact same shape; nothing else in this file, {@code
 * AiGatewayService}, or any business service needs to change.
 */
@Configuration
@EnableConfigurationProperties(SpringAiMigrationProperties.class)
public class ProviderRegistryConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistryConfig.class);

    @Bean
    public LlmProvider geminiLlmProvider(SpringAiMigrationProperties migrationProps, AiGatewayProperties props) {
        if (migrationProps.getGemini().isEnabled()) {
            log.info("Provider registry: 'gemini' resolved to Spring AI implementation (spring-ai.providers.gemini.enabled=true)");
            return new GeminiSpringAiProvider(props);
        }
        log.info("Provider registry: 'gemini' resolved to legacy implementation (spring-ai.providers.gemini.enabled=false)");
        return new GeminiProvider(props);
    }
}
