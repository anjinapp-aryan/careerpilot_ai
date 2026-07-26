package ai.careerpilot.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@code lastSuccessAt} tracking, added to surface "last successful request" in
 * {@code AiGatewayService.providerStatuses()} (part of the SambaNova health-reporting
 * extension) — a separate, non-expiring signal distinct from {@code getStatus()}'s
 * 5-minute-TTL health cache, which a later failure or TTL expiry can overwrite/clear.
 */
class ProviderHealthTrackerTest {

    @Test
    void lastSuccessAtIsNullBeforeAnySuccessEverRecorded() {
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        assertThat(tracker.getLastSuccessAt("never-called")).isNull();
    }

    @Test
    void recordSuccessSetsLastSuccessAt() {
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        Instant before = Instant.now();

        tracker.recordSuccess("deepseek_flash");

        Instant lastSuccess = tracker.getLastSuccessAt("deepseek_flash");
        assertThat(lastSuccess).isNotNull();
        assertThat(lastSuccess).isAfterOrEqualTo(before);
    }

    @Test
    void lastSuccessAtSurvivesASubsequentFailure_unlikeGetStatus() {
        ProviderHealthTracker tracker = new ProviderHealthTracker();

        tracker.recordSuccess("kimi");
        Instant recordedSuccess = tracker.getLastSuccessAt("kimi");
        tracker.recordFailure("kimi", "404 not entitled");

        // getStatus() now reflects the failure...
        assertThat(tracker.getStatus("kimi")).isEqualTo(ProviderHealthTracker.Status.DEGRADED);
        // ...but lastSuccessAt still remembers the earlier success, unchanged.
        assertThat(tracker.getLastSuccessAt("kimi")).isEqualTo(recordedSuccess);
    }

    @Test
    void lastSuccessAtIsPerProvider() {
        ProviderHealthTracker tracker = new ProviderHealthTracker();
        tracker.recordSuccess("sambanova_deepseek_v3_2");

        assertThat(tracker.getLastSuccessAt("sambanova_deepseek_v3_2")).isNotNull();
        assertThat(tracker.getLastSuccessAt("sambanova_gpt_oss_120b")).isNull();
    }
}
