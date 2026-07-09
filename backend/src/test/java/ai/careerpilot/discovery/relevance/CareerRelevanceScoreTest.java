package ai.careerpilot.discovery.relevance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The 40/30/20/10 weighted composite and its match-strength bands. */
class CareerRelevanceScoreTest {

    private static JobEligibilityEngine.Result result(boolean roleMatch, int roleSimilarity,
                                                       boolean experienceMatch, int skillOverlap,
                                                       boolean domainMatch) {
        var verdict = !roleMatch ? JobEligibilityEngine.Verdict.REJECTED_ROLE
                : !experienceMatch ? JobEligibilityEngine.Verdict.REJECTED_EXPERIENCE
                : skillOverlap < 1 ? JobEligibilityEngine.Verdict.REJECTED_SKILLS
                : !domainMatch ? JobEligibilityEngine.Verdict.REJECTED_DOMAIN
                : JobEligibilityEngine.Verdict.ELIGIBLE;
        return new JobEligibilityEngine.Result(verdict, roleMatch, roleSimilarity, experienceMatch, skillOverlap, domainMatch);
    }

    @Test
    void perfectMatchScoresOneHundred() {
        CareerRelevanceScore score = CareerRelevanceScore.from(result(true, 100, true, 100, true));
        assertEquals(100, score.relevanceScore());
        assertEquals("Strong Match", score.matchStrength());
        assertTrue(score.clearsFeedThreshold());
    }

    @Test
    void weightedFormulaMatchesSpec() {
        // role=100*0.40 + skill=50*0.30 + experience(true=100)*0.20 + domain(false=0)*0.10
        // = 40 + 15 + 20 + 0 = 75
        CareerRelevanceScore score = CareerRelevanceScore.from(result(true, 100, true, 50, false));
        assertEquals(75, score.relevanceScore());
        assertEquals("Good Match", score.matchStrength());
    }

    @Test
    void matchStrengthBands() {
        assertEquals("Strong Match", CareerRelevanceScore.matchStrengthFor(97));
        assertEquals("Excellent Match", CareerRelevanceScore.matchStrengthFor(88));
        assertEquals("Good Match", CareerRelevanceScore.matchStrengthFor(72));
        assertEquals("Weak Match", CareerRelevanceScore.matchStrengthFor(63));
        assertEquals("Hidden", CareerRelevanceScore.matchStrengthFor(40));
    }

    @Test
    void belowSeventyDoesNotClearFeedThreshold() {
        CareerRelevanceScore score = CareerRelevanceScore.from(result(true, 50, false, 30, false));
        assertFalse(score.clearsFeedThreshold());
    }

    @Test
    void rejectedRoleScoresLow() {
        CareerRelevanceScore score = CareerRelevanceScore.from(result(false, 0, true, 80, true));
        // role=0*0.40 + skill=80*0.30 + experience=100*0.20 + domain=100*0.10 = 0+24+20+10 = 54
        assertEquals(54, score.relevanceScore());
        assertFalse(score.clearsFeedThreshold());
    }
}
