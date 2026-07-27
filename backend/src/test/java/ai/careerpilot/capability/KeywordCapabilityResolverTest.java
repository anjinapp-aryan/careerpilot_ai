package ai.careerpilot.capability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordCapabilityResolverTest {

    private final KeywordCapabilityResolver resolver = new KeywordCapabilityResolver();

    @Test
    void resolvesResumeAnalysis() {
        assertThat(resolver.resolve("What is my latest uploaded resume?")).isEqualTo(CapabilityType.RESUME_ANALYSIS);
    }

    @Test
    void resolvesCareerStrategy() {
        assertThat(resolver.resolve("Summarise my career strategy.")).isEqualTo(CapabilityType.CAREER_STRATEGY);
    }

    @Test
    void resolvesGithubReview() {
        assertThat(resolver.resolve("Analyse my GitHub profile.")).isEqualTo(CapabilityType.GITHUB_REVIEW);
    }

    @Test
    void resolvesInterviewPreparation() {
        assertThat(resolver.resolve("Help me prepare for my interview")).isEqualTo(CapabilityType.INTERVIEW_PREPARATION);
    }

    @Test
    void resolvesLearningHelp() {
        assertThat(resolver.resolve("Explain Spring AI ToolCallback.")).isEqualTo(CapabilityType.LEARNING_HELP);
    }

    @Test
    void resolvesJobRecommendation() {
        assertThat(resolver.resolve("Can you recommend a job for me?")).isEqualTo(CapabilityType.JOB_RECOMMENDATION);
    }

    @Test
    void returnsNullForUnrelatedMessage() {
        assertThat(resolver.resolve("What's the weather like today?")).isNull();
    }

    @Test
    void returnsNullForBlankOrNullMessage() {
        assertThat(resolver.resolve("")).isNull();
        assertThat(resolver.resolve(null)).isNull();
    }
}
