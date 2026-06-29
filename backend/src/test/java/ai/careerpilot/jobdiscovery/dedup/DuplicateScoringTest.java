package ai.careerpilot.jobdiscovery.dedup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-function coverage for the text-confirmation signals used to guard embedding-similarity
 * candidates against false positives (two different roles at the same company reading similar).
 */
class DuplicateScoringTest {

    private final DuplicateScoring scoring = new DuplicateScoring();

    @Test
    void companyMatchesIgnoresLegalSuffixAndCase() {
        assertTrue(scoring.companyMatches("Acme Inc.", "ACME"));
        assertTrue(scoring.companyMatches("Stripe, LLC", "stripe"));
        assertTrue(scoring.companyMatches("Globex GmbH", "Globex"));
    }

    @Test
    void companyMatchesRejectsDifferentCompanies() {
        assertFalse(scoring.companyMatches("Acme Inc.", "Acme Industries"));
        assertFalse(scoring.companyMatches("Stripe", "Square"));
    }

    @Test
    void companyMatchesRejectsBlank() {
        assertFalse(scoring.companyMatches(null, "Acme"));
        assertFalse(scoring.companyMatches("", ""));
    }

    @Test
    void titleJaccardIsOneForIdenticalTitles() {
        assertEquals(1.0, scoring.titleJaccard("Senior Backend Engineer", "Senior Backend Engineer"), 0.001);
    }

    @Test
    void titleJaccardIsHighForReorderedOrPunctuatedTitles() {
        double sim = scoring.titleJaccard("Senior Backend Engineer (Java)", "Backend Engineer, Senior");
        assertTrue(sim > 0.7, "expected high similarity, got " + sim);
    }

    @Test
    void titleJaccardIsLowForDifferentRoles() {
        double sim = scoring.titleJaccard("Senior Backend Engineer", "Marketing Coordinator");
        assertTrue(sim < 0.2, "expected low similarity, got " + sim);
    }

    @Test
    void titleJaccardIsZeroForBlankInput() {
        assertEquals(0.0, scoring.titleJaccard(null, "Engineer"), 0.001);
        assertEquals(0.0, scoring.titleJaccard("", ""), 0.001);
    }
}
