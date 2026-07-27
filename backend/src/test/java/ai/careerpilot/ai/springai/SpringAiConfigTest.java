package ai.careerpilot.ai.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.2.1 validation sprint — {@link SpringAiConfig} had no regression test since Phase
 * 9.1 shipped it (verified only via a temporary, deleted {@code ApplicationRunner} smoke test at
 * the time). This closes that gap: confirms the dark-by-default guarantee (flag off → zero
 * beans) and that flag-on construction succeeds even with a blank api-key (the whole point of
 * the "unset" placeholder documented in {@link SpringAiConfig#springAiOpenAiClient()} — bean
 * construction must never crash the app just because a real key wasn't supplied yet).
 */
class SpringAiConfigTest {

    @Configuration
    @EnableConfigurationProperties(SpringAiFoundationProperties.class)
    static class Wrapper {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Wrapper.class, SpringAiConfig.class);

    @Test
    void withFlagAtDefault_noSpringAiBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ChatModel.class);
            assertThat(context).doesNotHaveBean(StreamingChatModel.class);
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
        });
    }

    @Test
    void withFlagOnAndBlankApiKey_beansConstructWithoutThrowing() {
        contextRunner.withPropertyValues("ai.springai.foundation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            // Not hasSingleBean(ChatModel.class): springAiStreamingChatModel returns the SAME
            // instance as springAiChatModel (see SpringAiConfig's own javadoc), so both bean
            // names satisfy a ChatModel-typed lookup — two names, one real object.
            assertThat(context).hasBean("springAiChatModel");
            assertThat(context.getBean("springAiChatModel")).isInstanceOf(ChatModel.class);
            assertThat(context).hasSingleBean(EmbeddingModel.class);
        });
    }

    @Test
    void streamingChatModelResolvesToTheSameInstanceAsChatModel_viaPrimary() {
        contextRunner.withPropertyValues("ai.springai.foundation.enabled=true").run(context -> {
            ChatModel chatModel = context.getBean(ChatModel.class);
            StreamingChatModel streamingChatModel = context.getBean(StreamingChatModel.class);
            assertThat(streamingChatModel).isSameAs(chatModel);
        });
    }
}
