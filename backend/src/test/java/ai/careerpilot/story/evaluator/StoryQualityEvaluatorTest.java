package ai.careerpilot.story.evaluator;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoryQualityEvaluatorTest {

    private final StoryQualityEvaluator evaluator = new StoryQualityEvaluator();

    private StarStory.StarStoryBuilder blank() {
        return StarStory.builder().storyType(StoryType.SUCCESS).status(StoryStatus.DRAFT).source(StorySource.MANUAL)
                .currentVersion(1);
    }

    @Test
    void emptyStoryScoresLowAndListsAllMissingSections() {
        StarStory story = blank().build();
        var eval = evaluator.evaluate(story);
        assertTrue(eval.qualityScore() < 40, "empty story should score low: " + eval.qualityScore());
        assertTrue(eval.missingSections().contains("Situation"));
        assertTrue(eval.missingSections().contains("Task"));
        assertTrue(eval.missingSections().contains("Action"));
        assertTrue(eval.missingSections().contains("Result"));
    }

    @Test
    void fullyPopulatedStoryScoresHigherThanEmpty() {
        StarStory rich = blank()
                .situation("At my previous company we faced a major outage impacting thousands of users.")
                .task("I was responsible for coordinating the incident response across three teams.")
                .action("I led the war room, triaged logs, and coordinated a rollback with the platform team.")
                .result("We restored service in 45 minutes and reduced future incidents by 30%.")
                .reflection("I learned the value of clear ownership during incidents.")
                .lessonsLearned("Always have a rollback plan ready before deploying.")
                .businessImpact("Saved an estimated $50000 in lost revenue.")
                .evidence("Postmortem doc linked in the internal wiki, reviewed by leadership.")
                .build();
        var richEval = evaluator.evaluate(rich);
        var emptyEval = evaluator.evaluate(blank().build());
        assertTrue(richEval.qualityScore() > emptyEval.qualityScore());
        assertTrue(richEval.missingSections().isEmpty());
    }

    @Test
    void breakdownContainsAllNineSubScoresPlusOverall() {
        var eval = evaluator.evaluate(blank().situation("x").task("y").action("z").result("1 improvement").build());
        for (String key : new String[]{"completeness", "starCorrectness", "technicalDepth", "businessImpact",
                "leadership", "communication", "confidence", "authenticity", "evidenceQuality", "overall"}) {
            assertTrue(eval.breakdown().containsKey(key), "missing breakdown key: " + key);
        }
    }

    @Test
    void allScoresAreClampedBetweenZeroAndHundred() {
        StarStory story = blank()
                .situation("s".repeat(500)).task("t".repeat(500)).action("a".repeat(500)).result("100% improvement")
                .businessImpact("$1000000 saved").evidence("e".repeat(500))
                .build();
        var eval = evaluator.evaluate(story);
        eval.breakdown().values().forEach(v -> assertTrue(v >= 0 && v <= 100, "out of range: " + v));
        assertTrue(eval.qualityScore() >= 0 && eval.qualityScore() <= 100);
        assertTrue(eval.confidenceScore() >= 0 && eval.confidenceScore() <= 100);
    }

    @Test
    void resultWithoutNumberLowersStarCorrectness() {
        StarStory withNumber = blank().situation("context here that is long enough").task("responsibility statement")
                .action("did concrete things here that matter").result("improved throughput by 30%").build();
        StarStory withoutNumber = blank().situation("context here that is long enough").task("responsibility statement")
                .action("did concrete things here that matter").result("improved throughput significantly").build();
        assertTrue(evaluator.evaluate(withNumber).breakdown().get("starCorrectness")
                > evaluator.evaluate(withoutNumber).breakdown().get("starCorrectness"));
    }

    @Test
    void explicitConfidenceScoreIsRespected() {
        StarStory story = blank().confidenceScore(90).build();
        assertEquals(90, evaluator.evaluate(story).confidenceScore());
    }

    @Test
    void missingEvidenceIsFlaggedAndSuggested() {
        StarStory story = blank().situation("a").task("b").action("c").result("d").build();
        var eval = evaluator.evaluate(story);
        assertTrue(eval.missingSections().contains("Evidence"));
        assertTrue(eval.improvementSuggestions().stream().anyMatch(s -> s.toLowerCase().contains("evidence")));
    }

    @Test
    void suggestionsListIsNeverEmpty() {
        StarStory story = blank()
                .situation("s".repeat(100)).task("t".repeat(100)).action("a".repeat(100)).result("50% improvement")
                .reflection("r").lessonsLearned("l").businessImpact("saved $100").evidence("e".repeat(100))
                .build();
        assertFalse(evaluator.evaluate(story).improvementSuggestions().isEmpty());
    }
}
