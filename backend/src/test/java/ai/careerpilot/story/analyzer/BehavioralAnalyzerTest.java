package ai.careerpilot.story.analyzer;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.story.BehavioralCompetency;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BehavioralAnalyzerTest {

    private final BehavioralAnalyzer analyzer = new BehavioralAnalyzer();

    private StarStory.StarStoryBuilder base() {
        return StarStory.builder().storyType(StoryType.LEADERSHIP).status(StoryStatus.DRAFT).source(StorySource.MANUAL)
                .currentVersion(1);
    }

    @Test
    void classifyReturnsAllEighteenCompetencies() {
        Map<BehavioralCompetency, Integer> scores = analyzer.classify(base().situation("").build());
        assertEquals(BehavioralCompetency.values().length, scores.size());
    }

    @Test
    void leadershipKeywordsScoreLeadershipHigh() {
        StarStory story = base().action("I led the team and directed the rollout, owned the outcome.").build();
        assertTrue(analyzer.classify(story).get(BehavioralCompetency.LEADERSHIP) > 0);
    }

    @Test
    void noKeywordsScoreZero() {
        StarStory story = base().situation("nothing relevant here at all").build();
        assertEquals(0, analyzer.classify(story).get(BehavioralCompetency.CLOUD));
    }

    @Test
    void cloudKeywordsDetected() {
        StarStory story = base().action("Migrated the service to AWS using Kubernetes and Docker.").build();
        assertTrue(analyzer.classify(story).get(BehavioralCompetency.CLOUD) > 0);
    }

    @Test
    void mentorshipKeywordsDetected() {
        StarStory story = base().action("I mentored two junior engineers and coached them through onboarding.").build();
        assertTrue(analyzer.classify(story).get(BehavioralCompetency.MENTORSHIP) > 0);
    }

    @Test
    void topCompetenciesRespectsLimitAndExcludesZeroHits() {
        StarStory story = base().action("Led the migration to AWS and mentored the team.")
                .result("Delivered ahead of deadline.").build();
        List<BehavioralCompetency> top = analyzer.topCompetencies(story, 3);
        assertTrue(top.size() <= 3);
        top.forEach(c -> assertTrue(analyzer.classify(story).get(c) > 0));
    }

    @Test
    void scoresAreClampedAtOneHundred() {
        StarStory story = base().action(("led led led led directed directed owned owned drove drove ").repeat(5)).build();
        analyzer.classify(story).values().forEach(v -> assertTrue(v <= 100));
    }
}
