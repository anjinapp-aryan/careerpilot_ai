package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the phase spec's own worked examples end-to-end through resolver + classifier. */
class KeywordIntentResolverTest {

    private final IntentRegistry registry = new InMemoryIntentRegistry();
    private final KeywordIntentResolver resolver = new KeywordIntentResolver(registry);
    private final DefaultIntentClassifier classifier = new DefaultIntentClassifier(registry);

    private IntentType classify(String message) {
        List<IntentCandidate> ranked = classifier.classify(resolver.resolve(message));
        return ranked.isEmpty() ? null : ranked.get(0).type();
    }

    @Test
    void resumeRejectionExample() {
        assertThat(classify("I keep getting rejected")).isEqualTo(IntentType.RESUME_ANALYSIS);
    }

    @Test
    void salaryExample() {
        assertThat(classify("I want a better salary")).isEqualTo(IntentType.CAREER_STRATEGY);
    }

    @Test
    void interviewExample() {
        assertThat(classify("Can I crack Amazon?")).isEqualTo(IntentType.INTERVIEW_PREPARATION);
    }

    @Test
    void executiveCoachExample() {
        assertThat(classify("I'm confused about my career")).isEqualTo(IntentType.EXECUTIVE_COACH);
    }

    @Test
    void githubExample() {
        assertThat(classify("Review my GitHub")).isEqualTo(IntentType.GITHUB_ANALYSIS);
    }

    @Test
    void unrelatedMessageResolvesToNoCandidates() {
        assertThat(resolver.resolve("what's the weather like?")).isEmpty();
    }

    @Test
    void blankOrNullMessageResolvesToNoCandidates() {
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void singleKeywordMatchYieldsMediumConfidenceScore() {
        List<IntentCandidate> candidates = resolver.resolve("Review my GitHub");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).score()).isEqualTo(0.5);
    }
}
