package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.springai.SpringAiFoundationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 10.2.1 validation sprint — {@link SpringAiLlmProvider} (Phase 9.2's canary provider) had
 * no regression test; verified only via a temporary, deleted smoke test at the time. This closes
 * that gap: confirms the "provider always registered, isConfigured() gates real use" contract
 * that lets {@code AiGatewayService} skip it safely regardless of flag state — the exact
 * mechanism CLAUDE.md documents for this class.
 */
class SpringAiLlmProviderTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> providerFor(ChatModel model) {
        ObjectProvider<ChatModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<StreamingChatModel> providerFor(StreamingChatModel model) {
        ObjectProvider<StreamingChatModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    @Test
    void notConfiguredWhenFoundationFlagIsOff() {
        SpringAiFoundationProperties props = new SpringAiFoundationProperties();
        props.setEnabled(false);
        props.setApiKey("real-key");
        SpringAiLlmProvider provider = new SpringAiLlmProvider(
                props, providerFor(mock(ChatModel.class)), providerFor(mock(StreamingChatModel.class)));

        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void notConfiguredWhenChatModelBeanAbsent_evenIfFlagOnAndKeySet() {
        SpringAiFoundationProperties props = new SpringAiFoundationProperties();
        props.setEnabled(true);
        props.setApiKey("real-key");
        SpringAiLlmProvider provider = new SpringAiLlmProvider(
                props, providerFor((ChatModel) null), providerFor(mock(StreamingChatModel.class)));

        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void notConfiguredWhenApiKeyBlank_evenIfFlagOnAndBeansPresent() {
        SpringAiFoundationProperties props = new SpringAiFoundationProperties();
        props.setEnabled(true);
        props.setApiKey("");
        SpringAiLlmProvider provider = new SpringAiLlmProvider(
                props, providerFor(mock(ChatModel.class)), providerFor(mock(StreamingChatModel.class)));

        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void configuredOnlyWhenFlagOnAndBeansPresentAndKeySet() {
        SpringAiFoundationProperties props = new SpringAiFoundationProperties();
        props.setEnabled(true);
        props.setApiKey("real-key");
        SpringAiLlmProvider provider = new SpringAiLlmProvider(
                props, providerFor(mock(ChatModel.class)), providerFor(mock(StreamingChatModel.class)));

        assertThat(provider.isConfigured()).isTrue();
    }

    @Test
    void nameAndDisplayNameMatchCanaryConvention() {
        SpringAiFoundationProperties props = new SpringAiFoundationProperties();
        SpringAiLlmProvider provider = new SpringAiLlmProvider(
                props, providerFor((ChatModel) null), providerFor((StreamingChatModel) null));

        assertThat(provider.name()).isEqualTo("spring_ai");
        assertThat(provider.displayName()).isEqualTo("Spring AI (canary)");
    }
}
