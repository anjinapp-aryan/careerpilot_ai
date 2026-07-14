package ai.careerpilot.applications;

import ai.careerpilot.applications.ApplicationHealthService.HealthResult;
import ai.careerpilot.applications.ApplicationHealthService.HealthStatus;
import ai.careerpilot.applications.ApplicationRecommendationService.RecommendationAction;
import ai.careerpilot.applications.ApplicationRecommendationService.RecommendationResult;
import ai.careerpilot.domain.Application;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationRecommendationServiceTest {

    private final ApplicationRecommendationService svc = new ApplicationRecommendationService();

    private Application app(String status, Integer ats, Instant updatedAt) {
        return Application.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).orgId(UUID.randomUUID()).jobId(UUID.randomUUID())
                .status(status).atsScore(ats).updatedAt(updatedAt)
                .build();
    }

    private HealthResult health(HealthStatus status) {
        return new HealthResult(status, 50, "test");
    }

    @Test
    void rejectedRecommendsReapplyLater() {
        RecommendationResult r = svc.recommend(app("REJECTED", 70, Instant.now()), health(HealthStatus.COLD), Instant.now());
        assertThat(r.action()).isEqualTo(RecommendationAction.REAPPLY_LATER);
    }

    @Test
    void withdrawnRecommendsReapplyLater() {
        RecommendationResult r = svc.recommend(app("WITHDRAWN", null, Instant.now()), health(HealthStatus.COLD), Instant.now());
        assertThat(r.action()).isEqualTo(RecommendationAction.REAPPLY_LATER);
    }

    @Test
    void offerRecommendsWait() {
        RecommendationResult r = svc.recommend(app("OFFER", 90, Instant.now()), health(HealthStatus.EXCELLENT), Instant.now());
        assertThat(r.action()).isEqualTo(RecommendationAction.WAIT);
    }

    @Test
    void lowAtsScoreRecommendsImproveResume() {
        RecommendationResult r = svc.recommend(app("APPLIED", 30, Instant.now()), health(HealthStatus.NEEDS_ATTENTION), Instant.now());
        assertThat(r.action()).isEqualTo(RecommendationAction.IMPROVE_RESUME);
    }

    @Test
    void savedOverAWeekRecommendsOutreach() {
        Instant weekAgo = Instant.now().minus(8, ChronoUnit.DAYS);
        RecommendationResult r = svc.recommend(app("SAVED", null, weekAgo), health(HealthStatus.NEEDS_ATTENTION), weekAgo);
        assertThat(r.action()).isEqualTo(RecommendationAction.OUTREACH);
    }

    @Test
    void appliedOverThirtyDaysRecommendsWithdraw() {
        Instant longAgo = Instant.now().minus(35, ChronoUnit.DAYS);
        RecommendationResult r = svc.recommend(app("APPLIED", 70, longAgo), health(HealthStatus.STALE), longAgo);
        assertThat(r.action()).isEqualTo(RecommendationAction.WITHDRAW);
    }

    @Test
    void appliedOverFourteenDaysRecommendsFollowUpNow() {
        Instant twoWeeksAgo = Instant.now().minus(15, ChronoUnit.DAYS);
        RecommendationResult r = svc.recommend(app("APPLIED", 70, twoWeeksAgo), health(HealthStatus.NEEDS_ATTENTION), twoWeeksAgo);
        assertThat(r.action()).isEqualTo(RecommendationAction.FOLLOW_UP_NOW);
    }

    @Test
    void interviewingWithRiskHealthRecommendsNetwork() {
        RecommendationResult r = svc.recommend(app("INTERVIEWING", 70, Instant.now()), health(HealthStatus.RISK), Instant.now());
        assertThat(r.action()).isEqualTo(RecommendationAction.NETWORK);
    }

    @Test
    void riskHealthWithoutInterviewingRecommendsImproveCoverLetter() {
        RecommendationResult r = svc.recommend(app("APPLIED", 70, Instant.now()), health(HealthStatus.RISK), Instant.now());
        assertThat(r.action()).isEqualTo(RecommendationAction.IMPROVE_COVER_LETTER);
    }

    @Test
    void freshHealthyApplicationRecommendsWait() {
        RecommendationResult r = svc.recommend(app("APPLIED", 70, Instant.now()), health(HealthStatus.HEALTHY), Instant.now());
        assertThat(r.action()).isEqualTo(RecommendationAction.WAIT);
    }

    @Test
    void reasoningIsNeverBlank() {
        RecommendationResult r = svc.recommend(app("APPLIED", 70, Instant.now()), health(HealthStatus.HEALTHY), Instant.now());
        assertThat(r.reasoning()).isNotBlank();
    }
}
