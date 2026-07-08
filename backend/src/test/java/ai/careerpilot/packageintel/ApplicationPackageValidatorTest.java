package ai.careerpilot.packageintel;

import ai.careerpilot.packageintel.ApplicationPackageValidator.ValidationResult;
import ai.careerpilot.packageintel.ApplicationPackageValidator.ValidationSignals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure evaluate() gate matrix — deterministic, no mocks. Must never fabricate READY on a missing gate. */
class ApplicationPackageValidatorTest {

    private final ApplicationPackageValidator validator = new ApplicationPackageValidator();

    /** All applicable gates pass (optional engines off) → READY. */
    private static ValidationSignals allPass() {
        return new ValidationSignals(true, true, true, true,
                false, false, false, false, true, true);
    }

    @Test
    void everyGatePassIsReady() {
        ValidationResult r = validator.evaluate(allPass());
        assertEquals(PackageValidationStatus.READY, r.status());
        assertNull(r.blockingReason());
    }

    @Test
    void missingResumeIsBlocked() {
        ValidationSignals s = new ValidationSignals(false, true, true, true,
                false, false, false, false, true, true);
        ValidationResult r = validator.evaluate(s);
        assertEquals(PackageValidationStatus.BLOCKED, r.status());
        assertEquals("resume-selected", r.blockingReason());
    }

    @Test
    void missingRecommendationIsBlocked() {
        ValidationSignals s = new ValidationSignals(true, true, true, false,
                false, false, false, false, true, true);
        assertEquals(PackageValidationStatus.BLOCKED, validator.evaluate(s).status());
    }

    @Test
    void incompleteMandatoryFieldsIsBlocked() {
        ValidationSignals s = new ValidationSignals(true, true, true, true,
                false, false, false, false, true, false);
        ValidationResult r = validator.evaluate(s);
        assertEquals(PackageValidationStatus.BLOCKED, r.status());
        assertEquals("mandatory-fields-complete", r.blockingReason());
    }

    @Test
    void notTailoredIsHumanReview() {
        ValidationSignals s = new ValidationSignals(true, false, true, true,
                false, false, false, false, true, true);
        ValidationResult r = validator.evaluate(s);
        assertEquals(PackageValidationStatus.HUMAN_REVIEW, r.status());
        assertEquals("resume-tailored", r.blockingReason());
    }

    @Test
    void missingAtsIsHumanReview() {
        ValidationSignals s = new ValidationSignals(true, true, false, true,
                false, false, false, false, true, true);
        assertEquals(PackageValidationStatus.HUMAN_REVIEW, validator.evaluate(s).status());
    }

    @Test
    void requiredCompanyResearchMissingIsHumanReview() {
        // company research REQUIRED (engine enabled) but not available → review.
        ValidationSignals s = new ValidationSignals(true, true, true, true,
                false, true, false, false, true, true);
        assertEquals(PackageValidationStatus.HUMAN_REVIEW, validator.evaluate(s).status());
    }

    @Test
    void companyResearchNotRequiredDoesNotDowngrade() {
        // engine OFF → absence must not affect the verdict.
        ValidationSignals s = new ValidationSignals(true, true, true, true,
                false, false, false, false, true, true);
        assertEquals(PackageValidationStatus.READY, validator.evaluate(s).status());
    }

    @Test
    void requiredLearningMissingIsHumanReview() {
        ValidationSignals s = new ValidationSignals(true, true, true, true,
                false, false, false, true, true, true);
        assertEquals(PackageValidationStatus.HUMAN_REVIEW, validator.evaluate(s).status());
    }

    @Test
    void blockOutranksReview() {
        // both a hard and a soft gate fail → BLOCKED wins.
        ValidationSignals s = new ValidationSignals(false, false, false, true,
                false, false, false, false, true, true);
        assertEquals(PackageValidationStatus.BLOCKED, validator.evaluate(s).status());
    }

    @Test
    void missingRequiredSkillsIsHumanReview() {
        ValidationSignals s = new ValidationSignals(true, true, true, true,
                false, false, false, false, false, true);
        assertEquals(PackageValidationStatus.HUMAN_REVIEW, validator.evaluate(s).status());
    }
}
