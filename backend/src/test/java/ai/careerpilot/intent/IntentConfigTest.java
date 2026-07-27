package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntentConfig} — dark-by-default guarantee: with {@code intent.engine.enabled} at its
 * default ({@code false}), none of this package's beans are constructed.
 */
class IntentConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IntentConfig.class);

    @Test
    void withFlagAtDefault_noIntentBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(IntentRegistry.class);
            assertThat(context).doesNotHaveBean(IntentResolver.class);
            assertThat(context).doesNotHaveBean(IntentClassifier.class);
            assertThat(context).doesNotHaveBean(IntentHistory.class);
            assertThat(context).doesNotHaveBean(IntentMetrics.class);
            assertThat(context).doesNotHaveBean(IntentEngine.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("intent.engine.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(IntentRegistry.class);
            assertThat(context).hasSingleBean(IntentResolver.class);
            assertThat(context).hasSingleBean(IntentClassifier.class);
            assertThat(context).hasSingleBean(IntentHistory.class);
            assertThat(context).hasSingleBean(IntentMetrics.class);
            assertThat(context).hasSingleBean(IntentEngine.class);
            assertThat(context.getBean(IntentRegistry.class)).isInstanceOf(InMemoryIntentRegistry.class);
        });
    }

    @Test
    void endToEnd_wiredEngineClassifiesRealMessage() {
        contextRunner.withPropertyValues("intent.engine.enabled=true").run(context -> {
            IntentEngine engine = context.getBean(IntentEngine.class);
            IntentResult result = engine.analyze(null, "Review my GitHub");
            assertThat(result.intentType()).isEqualTo(IntentType.GITHUB_ANALYSIS);
        });
    }
}
