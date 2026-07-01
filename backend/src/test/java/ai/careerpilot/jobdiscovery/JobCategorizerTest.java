package ai.careerpilot.jobdiscovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2B-1 → refined 2C-2 — pins the 5-tier score→category boundaries and the flag default. The
 * matcher only persists gate-passing jobs (score >= 70), so AUTO_APPLY_READY/HIGH_PRIORITY/
 * HUMAN_REVIEW/RECOMMENDED are what reach the table in practice, but the categorizer is a total
 * function over 0–100 so ARCHIVED is covered too.
 */
class JobCategorizerTest {

    private final JobCategorizer categorizer = new JobCategorizer(true);

    @Test
    void autoApplyReadyAtNinetyFiveAndAbove() {
        assertEquals(JobCategory.AUTO_APPLY_READY, categorizer.categorize(100));
        assertEquals(JobCategory.AUTO_APPLY_READY, categorizer.categorize(95));
    }

    @Test
    void highPriorityNinetyToNinetyFour() {
        assertEquals(JobCategory.HIGH_PRIORITY, categorizer.categorize(94));
        assertEquals(JobCategory.HIGH_PRIORITY, categorizer.categorize(90));
    }

    @Test
    void humanReviewEightyToEightyNine() {
        assertEquals(JobCategory.HUMAN_REVIEW, categorizer.categorize(89));
        assertEquals(JobCategory.HUMAN_REVIEW, categorizer.categorize(80));
    }

    @Test
    void recommendedSeventyToSeventyNine() {
        assertEquals(JobCategory.RECOMMENDED, categorizer.categorize(79));
        assertEquals(JobCategory.RECOMMENDED, categorizer.categorize(70));
    }

    @Test
    void archivedBelowSeventy() {
        assertEquals(JobCategory.ARCHIVED, categorizer.categorize(69));
        assertEquals(JobCategory.ARCHIVED, categorizer.categorize(0));
    }

    @Test
    void enabledFlagReflectsConstruction() {
        assertTrue(new JobCategorizer(true).isEnabled());
        assertFalse(new JobCategorizer(false).isEnabled());
    }
}
