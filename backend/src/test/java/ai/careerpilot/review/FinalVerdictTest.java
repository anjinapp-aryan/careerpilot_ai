package ai.careerpilot.review;

import ai.careerpilot.packageintel.PackageValidationStatus;
import ai.careerpilot.review.reviewer.QualityReviewer.QualityResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pipeline's pure final-verdict rule — READY only on strong+consistent+non-blocked. */
class FinalVerdictTest {

    private static PackageValidationStatus verdict(QualityCategory cat, int score, ConsistencyStatus c, String pkg) {
        return ApplicationReviewPipeline.finalVerdict(new QualityResult(score, cat), c, pkg);
    }

    @Test
    void strongConsistentIsReady() {
        assertEquals(PackageValidationStatus.READY,
                verdict(QualityCategory.STRONG, 80, ConsistencyStatus.PASS, "READY"));
    }

    @Test
    void consistencyFailIsBlocked() {
        assertEquals(PackageValidationStatus.BLOCKED,
                verdict(QualityCategory.EXCELLENT, 95, ConsistencyStatus.FAIL, "READY"));
    }

    @Test
    void blockedCategoryIsBlocked() {
        assertEquals(PackageValidationStatus.BLOCKED,
                verdict(QualityCategory.BLOCKED, 40, ConsistencyStatus.PASS, "READY"));
    }

    @Test
    void blockedPackageValidationIsBlocked() {
        assertEquals(PackageValidationStatus.BLOCKED,
                verdict(QualityCategory.STRONG, 85, ConsistencyStatus.PASS, "BLOCKED"));
    }

    @Test
    void warningConsistencyIsHumanReview() {
        assertEquals(PackageValidationStatus.HUMAN_REVIEW,
                verdict(QualityCategory.STRONG, 80, ConsistencyStatus.WARNING, "READY"));
    }

    @Test
    void weakQualityIsHumanReview() {
        assertEquals(PackageValidationStatus.HUMAN_REVIEW,
                verdict(QualityCategory.WEAK, 55, ConsistencyStatus.PASS, "HUMAN_REVIEW"));
    }
}
