package ai.careerpilot.applications;

import ai.careerpilot.applications.ApplicationHealthService.HealthResult;
import ai.careerpilot.applications.ApplicationHealthService.HealthStatus;
import ai.careerpilot.domain.Application;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic-formula coverage for {@link ApplicationHealthService} — same style as
 * {@code ApplicationStatusMachineTest} (pure, no mocks needed).
 */
class ApplicationHealthServiceTest {

    private final ApplicationHealthService svc = new ApplicationHealthService();

    private Application app(String status, Integer match, Integer ats, Instant updatedAt) {
        return Application.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).orgId(UUID.randomUUID()).jobId(UUID.randomUUID())
                .status(status).matchScore(match).atsScore(ats).updatedAt(updatedAt)
                .build();
    }

    @Test
    void strongFreshApplicationIsExcellentOrHealthy() {
        HealthResult r = svc.evaluate(app("APPLIED", 90, 85, Instant.now()), Instant.now());
        assertThat(r.status()).isIn(HealthStatus.EXCELLENT, HealthStatus.HEALTHY);
        assertThat(r.score()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void offerStatusBoostsScore() {
        HealthResult r = svc.evaluate(app("OFFER", 80, 80, Instant.now()), Instant.now());
        assertThat(r.status()).isEqualTo(HealthStatus.EXCELLENT);
    }

    @Test
    void rejectedIsAlwaysCold() {
        HealthResult r = svc.evaluate(app("REJECTED", 90, 90, Instant.now()), Instant.now());
        assertThat(r.status()).isEqualTo(HealthStatus.COLD);
    }

    @Test
    void withdrawnIsAlwaysCold() {
        HealthResult r = svc.evaluate(app("WITHDRAWN", null, null, Instant.now()), Instant.now());
        assertThat(r.status()).isEqualTo(HealthStatus.COLD);
    }

    @Test
    void staleAfterThreeWeeksOfNoMovement() {
        Instant longAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        HealthResult r = svc.evaluate(app("APPLIED", 60, 60, longAgo), longAgo);
        assertThat(r.status()).isEqualTo(HealthStatus.STALE);
    }

    @Test
    void weakScoresAndNoRecencyPenaltyStillLandInRiskOrAttention() {
        HealthResult r = svc.evaluate(app("APPLIED", 20, 20, Instant.now()), Instant.now());
        assertThat(r.status()).isIn(HealthStatus.RISK, HealthStatus.NEEDS_ATTENTION);
        assertThat(r.score()).isLessThan(60);
    }

    @Test
    void nullScoresDoNotThrowAndYieldNeutralBase() {
        HealthResult r = svc.evaluate(app("SAVED", null, null, Instant.now()), Instant.now());
        assertThat(r.score()).isEqualTo(50);
        assertThat(r.status()).isEqualTo(HealthStatus.NEEDS_ATTENTION);
    }

    @Test
    void reasoningIsNeverBlank() {
        HealthResult r = svc.evaluate(app("APPLIED", 70, 70, Instant.now()), Instant.now());
        assertThat(r.reasoning()).isNotBlank();
    }

    @Test
    void scoreIsClampedToZeroToHundred() {
        Instant veryStale = Instant.now().minus(200, ChronoUnit.DAYS);
        HealthResult r = svc.evaluate(app("APPLIED", 10, 10, veryStale), veryStale);
        assertThat(r.score()).isBetween(0, 100);
    }
}
