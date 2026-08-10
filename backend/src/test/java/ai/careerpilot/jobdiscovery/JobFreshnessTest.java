package ai.careerpilot.jobdiscovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Global Job Discovery Expansion — {@link JobFreshness} banding. */
class JobFreshnessTest {

    private final Instant now = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void within24HoursIsVeryFresh() {
        Instant posted = now.minus(23, ChronoUnit.HOURS);
        assertThat(JobFreshness.classify(posted, null, now)).isEqualTo(JobFreshness.VERY_FRESH);
    }

    @Test
    void within72HoursIsFresh() {
        Instant posted = now.minus(3, ChronoUnit.DAYS);
        assertThat(JobFreshness.classify(posted, null, now)).isEqualTo(JobFreshness.FRESH);
    }

    @Test
    void within7DaysIsRecent() {
        Instant posted = now.minus(6, ChronoUnit.DAYS);
        assertThat(JobFreshness.classify(posted, null, now)).isEqualTo(JobFreshness.RECENT);
    }

    @Test
    void within14DaysIsAging() {
        Instant posted = now.minus(10, ChronoUnit.DAYS);
        assertThat(JobFreshness.classify(posted, null, now)).isEqualTo(JobFreshness.AGING);
    }

    @Test
    void beyond14DaysIsStale() {
        Instant posted = now.minus(30, ChronoUnit.DAYS);
        assertThat(JobFreshness.classify(posted, null, now)).isEqualTo(JobFreshness.STALE);
    }

    @Test
    void fallsBackToCreatedAtWhenNoPostedDate() {
        Instant created = now.minus(1, ChronoUnit.HOURS);
        assertThat(JobFreshness.classify(null, created, now)).isEqualTo(JobFreshness.VERY_FRESH);
    }

    @Test
    void returnsNullRatherThanGuessingWhenNoTimestampExists() {
        assertThat(JobFreshness.classify(null, null, now)).isNull();
    }

    @Test
    void futureDatedPostingIsTreatedAsJustPostedNotAnError() {
        Instant future = now.plus(1, ChronoUnit.HOURS);
        assertThat(JobFreshness.classify(future, null, now)).isEqualTo(JobFreshness.VERY_FRESH);
    }
}
