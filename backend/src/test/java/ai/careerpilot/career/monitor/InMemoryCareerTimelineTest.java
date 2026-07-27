package ai.careerpilot.career.monitor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCareerTimelineTest {

    private final InMemoryCareerTimeline timeline = new InMemoryCareerTimeline();

    @Test
    void freshUserHasNoRecentlySurfacedAlerts() {
        assertThat(timeline.wasRecentlySurfaced(UUID.randomUUID(), CareerAlertType.JOB_MATCH, Duration.ofDays(7))).isFalse();
    }

    @Test
    void recordedAlertIsRecentlySurfacedWithinCooldown() {
        UUID userId = UUID.randomUUID();
        CareerAlert alert = CareerAlert.of(userId, CareerAlertType.RESUME_OUTDATED, CareerAlertSeverity.MEDIUM, "msg", Map.of());
        timeline.record(alert);

        assertThat(timeline.wasRecentlySurfaced(userId, CareerAlertType.RESUME_OUTDATED, Duration.ofDays(7))).isTrue();
    }

    @Test
    void differentAlertTypeIsNotConsideredRecentlySurfaced() {
        UUID userId = UUID.randomUUID();
        timeline.record(CareerAlert.of(userId, CareerAlertType.RESUME_OUTDATED, CareerAlertSeverity.MEDIUM, "msg", Map.of()));

        assertThat(timeline.wasRecentlySurfaced(userId, CareerAlertType.JOB_MATCH, Duration.ofDays(7))).isFalse();
    }

    @Test
    void recentForReturnsMostRecentFirst() {
        UUID userId = UUID.randomUUID();
        timeline.record(CareerAlert.of(userId, CareerAlertType.RESUME_OUTDATED, CareerAlertSeverity.MEDIUM, "first", Map.of()));
        timeline.record(CareerAlert.of(userId, CareerAlertType.JOB_MATCH, CareerAlertSeverity.HIGH, "second", Map.of()));

        List<CareerAlert> recent = timeline.recentFor(userId, 5);

        assertThat(recent.get(0).message()).isEqualTo("second");
    }

    @Test
    void isolatedPerUser() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        timeline.record(CareerAlert.of(userA, CareerAlertType.JOB_MATCH, CareerAlertSeverity.HIGH, "msg", Map.of()));

        assertThat(timeline.wasRecentlySurfaced(userB, CareerAlertType.JOB_MATCH, Duration.ofDays(7))).isFalse();
    }
}
