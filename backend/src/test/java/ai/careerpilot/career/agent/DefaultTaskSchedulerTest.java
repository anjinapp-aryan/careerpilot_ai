package ai.careerpilot.career.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTaskSchedulerTest {

    private final DefaultTaskScheduler scheduler = new DefaultTaskScheduler();

    @Test
    void freshUserIsEligibleToRun() {
        assertThat(scheduler.isEligibleToRun(UUID.randomUUID(), Duration.ofHours(24))).isTrue();
    }

    @Test
    void afterRecordingRun_notEligibleUntilIntervalElapses() {
        UUID userId = UUID.randomUUID();
        scheduler.recordRun(userId);

        assertThat(scheduler.isEligibleToRun(userId, Duration.ofHours(24))).isFalse();
    }

    @Test
    void eligibleAgainOnceIntervalHasPassed() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        scheduler.recordRun(userId);
        Thread.sleep(5); // guarantee real wall-clock progression past the recorded run

        assertThat(scheduler.isEligibleToRun(userId, Duration.ofMillis(1))).isTrue();
    }

    @Test
    void isolatedPerUser() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        scheduler.recordRun(userA);

        assertThat(scheduler.isEligibleToRun(userB, Duration.ofHours(24))).isTrue();
    }
}
