package ai.careerpilot.intent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 11.1 — the only place any Intent Engine bean is constructed, gated by the single {@code
 * intent.engine.enabled} flag (default {@code false} — matching the phase spec exactly, unlike
 * Phase 10.3's four-flag layering). With it off, none of these beans exist, and nothing outside
 * {@code ai.careerpilot.intent} references any of them — see the package javadoc for the "not
 * wired into Copilot yet" scope note; that wiring is Phase 11.2's Capability Planner's job, not
 * this phase's.
 */
@Configuration
public class IntentConfig {

    @Bean
    @ConditionalOnProperty(prefix = "intent.engine", name = "enabled", havingValue = "true")
    public IntentRegistry intentRegistry() {
        return new InMemoryIntentRegistry();
    }

    @Bean
    @ConditionalOnProperty(prefix = "intent.engine", name = "enabled", havingValue = "true")
    public IntentResolver intentResolver(IntentRegistry registry) {
        return new KeywordIntentResolver(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "intent.engine", name = "enabled", havingValue = "true")
    public IntentClassifier intentClassifier(IntentRegistry registry) {
        return new DefaultIntentClassifier(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "intent.engine", name = "enabled", havingValue = "true")
    public IntentHistory intentHistory() {
        return new InMemoryIntentHistory();
    }

    @Bean
    @ConditionalOnProperty(prefix = "intent.engine", name = "enabled", havingValue = "true")
    public IntentMetrics intentMetrics() {
        return new InMemoryIntentMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "intent.engine", name = "enabled", havingValue = "true")
    public IntentEngine intentEngine(IntentResolver resolver, IntentClassifier classifier,
                                      IntentHistory history, IntentMetrics metrics,
                                      @Value("${intent.engine.min-confidence:0.4}") double minConfidence) {
        return new DefaultIntentEngine(resolver, classifier, history, metrics, minConfidence);
    }
}
