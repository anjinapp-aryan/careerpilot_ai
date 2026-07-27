package ai.careerpilot.capability;

import ai.careerpilot.ai.AiGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link CapabilityConfig} — the master {@code capability.engine.enabled} flag must gate every
 * bean in this package. With it off (default), {@link AiGatewayService} (mocked here to stand
 * in for the real, unmodified bean) remains the only thing a caller could possibly use.
 */
class CapabilityConfigTest {

    @Configuration
    static class MockAiGateway {
        @Bean AiGatewayService aiGatewayService() { return mock(AiGatewayService.class); }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MockAiGateway.class, CapabilityConfig.class);

    @Test
    void withFlagAtDefault_noCapabilityBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(CapabilityRegistry.class);
            assertThat(context).doesNotHaveBean(CapabilityEngine.class);
            assertThat(context).doesNotHaveBean(CapabilityAwareChatService.class);
            assertThat(context).doesNotHaveBean(ToolSelectionEngine.class);
            assertThat(context).doesNotHaveBean(CapabilityMetrics.class);
        });
    }

    @Test
    void withMasterFlagOn_allBeansConstructedWithSubFlagsStillFalse() {
        contextRunner.withPropertyValues("capability.engine.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CapabilityRegistry.class);
            assertThat(context).hasSingleBean(CapabilityEngine.class);
            assertThat(context).hasSingleBean(CapabilityAwareChatService.class);
            assertThat(context.getBean(CapabilityRegistry.class)).isInstanceOf(InMemoryCapabilityRegistry.class);
        });
    }
}
