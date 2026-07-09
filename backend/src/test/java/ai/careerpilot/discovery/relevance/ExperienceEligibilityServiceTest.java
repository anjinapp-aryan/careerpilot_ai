package ai.careerpilot.discovery.relevance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the Phase 3B.1 spec's literal experience-bucket example exactly: a 12-yr candidate
 * against jobs requiring 2/5/8 years is rejected, and 15/20 years is allowed, using the default
 * 3-year tolerance (threshold = 12 - 3 = 9).
 */
class ExperienceEligibilityServiceTest {

    private final ExperienceEligibilityService service = new ExperienceEligibilityService(3);

    @Test
    void rejectsFarTooJuniorRoles_zeroToTwoYears() {
        assertFalse(service.isEligible(12, 2));
    }

    @Test
    void rejectsTooJuniorRoles_twoToFiveYears() {
        assertFalse(service.isEligible(12, 5));
    }

    @Test
    void rejectsStillTooJuniorRoles_fiveToEightYears() {
        assertFalse(service.isEligible(12, 8));
    }

    @Test
    void allowsSeniorRoles_eightToFifteenYears() {
        assertTrue(service.isEligible(12, 15));
    }

    @Test
    void allowsPrincipalRoles_fifteenPlusYears() {
        assertTrue(service.isEligible(12, 20));
    }

    @Test
    void noSignalOnEitherSideNeverRejects() {
        assertTrue(service.isEligible(null, 2));
        assertTrue(service.isEligible(12, null));
        assertTrue(service.isEligible(null, null));
    }

    @Test
    void toleranceIsConfigurable() {
        ExperienceEligibilityService loose = new ExperienceEligibilityService(10);
        assertTrue(loose.isEligible(12, 2)); // threshold = 12-10 = 2, 2 >= 2 → eligible
    }
}
