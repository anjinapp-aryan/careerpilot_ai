package ai.careerpilot.jobdiscovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the job-discovery provider observability layer: success/failure recording,
 * latency averaging, and the simple CLOSED/OPEN/HALF_OPEN circuit-state field described in
 * {@link JobDiscoveryHealthTracker}'s javadoc (diagnostics-only, does not gate fetch calls).
 */
class JobDiscoveryHealthTrackerTest {

    private JobDiscoveryHealthTracker tracker(long cooldownMs) {
        return new JobDiscoveryHealthTracker(cooldownMs);
    }

    @Test
    void unknownProviderReturnsClosedZeroedSnapshot() {
        var t = tracker(300_000);
        var s = t.snapshot("nonexistent");
        assertEquals("nonexistent", s.provider());
        assertEquals(JobDiscoveryHealthTracker.CircuitState.CLOSED, s.circuitState());
        assertEquals(0, s.totalRuns());
        assertEquals(0, s.successRuns());
        assertEquals(0, s.failureRuns());
        assertNull(s.lastRunAt());
        assertNull(s.lastError());
        assertEquals(0.0, s.successRate());
    }

    @Test
    void recordSuccessUpdatesCounters() {
        var t = tracker(300_000);
        t.recordSuccess("ashby", 120, 10, 8, 2);
        var s = t.snapshot("ashby");

        assertEquals(1, s.totalRuns());
        assertEquals(1, s.successRuns());
        assertEquals(0, s.failureRuns());
        assertEquals(120, s.lastLatencyMs());
        assertEquals(120, s.avgLatencyMs());
        assertEquals(10, s.lastJobsFetched());
        assertEquals(8, s.lastJobsAccepted());
        assertEquals(2, s.lastJobsRejected());
        assertNotNull(s.lastRunAt());
        assertNull(s.lastError());
        assertEquals(1.0, s.successRate());
        assertEquals(JobDiscoveryHealthTracker.CircuitState.CLOSED, s.circuitState());
    }

    @Test
    void recordFailureUpdatesCounters() {
        var t = tracker(300_000);
        t.recordFailure("smartrecruiters", 500, "timeout");
        var s = t.snapshot("smartrecruiters");

        assertEquals(1, s.totalRuns());
        assertEquals(0, s.successRuns());
        assertEquals(1, s.failureRuns());
        assertEquals("timeout", s.lastError());
        assertEquals(0.0, s.successRate());
    }

    @Test
    void avgLatencyIsAveragedAcrossRuns() {
        var t = tracker(300_000);
        t.recordSuccess("greenhouse", 100, 5, 5, 0);
        t.recordSuccess("greenhouse", 300, 5, 5, 0);
        var s = t.snapshot("greenhouse");

        assertEquals(2, s.totalRuns());
        assertEquals(200, s.avgLatencyMs());
        assertEquals(300, s.lastLatencyMs());
    }

    @Test
    void successRateMixesSuccessAndFailure() {
        var t = tracker(300_000);
        t.recordSuccess("lever", 100, 1, 1, 0);
        t.recordSuccess("lever", 100, 1, 1, 0);
        t.recordFailure("lever", 100, "boom");
        var s = t.snapshot("lever");

        assertEquals(3, s.totalRuns());
        assertEquals(2.0 / 3.0, s.successRate(), 0.0001);
    }

    @Test
    void consecutiveFailuresBelowThresholdStaysClosed() {
        var t = tracker(300_000);
        t.recordFailure("adzuna", 50, "e1");
        t.recordFailure("adzuna", 50, "e2");
        var s = t.snapshot("adzuna");

        assertEquals(JobDiscoveryHealthTracker.CircuitState.CLOSED, s.circuitState());
    }

    @Test
    void consecutiveFailuresAtThresholdOpensCircuit() {
        var t = tracker(300_000);
        t.recordFailure("adzuna", 50, "e1");
        t.recordFailure("adzuna", 50, "e2");
        t.recordFailure("adzuna", 50, "e3");
        var s = t.snapshot("adzuna");

        assertEquals(JobDiscoveryHealthTracker.CircuitState.OPEN, s.circuitState());
    }

    @Test
    void successResetsConsecutiveFailureCountAndClosesCircuit() {
        var t = tracker(300_000);
        t.recordFailure("jooble", 50, "e1");
        t.recordFailure("jooble", 50, "e2");
        t.recordFailure("jooble", 50, "e3");
        assertEquals(JobDiscoveryHealthTracker.CircuitState.OPEN, t.snapshot("jooble").circuitState());

        t.recordSuccess("jooble", 50, 1, 1, 0);
        assertEquals(JobDiscoveryHealthTracker.CircuitState.CLOSED, t.snapshot("jooble").circuitState());
    }

    @Test
    void openCircuitTransitionsToHalfOpenAfterCooldown() throws InterruptedException {
        var t = tracker(1); // 1ms cooldown so the test doesn't need to sleep long
        t.recordFailure("remoteok", 50, "e1");
        t.recordFailure("remoteok", 50, "e2");
        t.recordFailure("remoteok", 50, "e3");
        assertEquals(JobDiscoveryHealthTracker.CircuitState.OPEN, t.snapshot("remoteok").circuitState());

        Thread.sleep(20);
        assertEquals(JobDiscoveryHealthTracker.CircuitState.HALF_OPEN, t.snapshot("remoteok").circuitState());
    }

    @Test
    void openCircuitStaysOpenBeforeCooldownElapses() {
        var t = tracker(300_000);
        t.recordFailure("arbeitnow", 50, "e1");
        t.recordFailure("arbeitnow", 50, "e2");
        t.recordFailure("arbeitnow", 50, "e3");

        assertEquals(JobDiscoveryHealthTracker.CircuitState.OPEN, t.snapshot("arbeitnow").circuitState());
    }

    @Test
    void allSnapshotsReturnsEveryTrackedProvider() {
        var t = tracker(300_000);
        t.recordSuccess("ashby", 1, 1, 1, 0);
        t.recordSuccess("smartrecruiters", 1, 1, 1, 0);

        var all = t.allSnapshots();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("ashby"));
        assertTrue(all.containsKey("smartrecruiters"));
    }

    @Test
    void allSnapshotsEmptyWhenNothingTracked() {
        var t = tracker(300_000);
        assertTrue(t.allSnapshots().isEmpty());
    }

    @Test
    void resetClearsTrackedProviderBackToDefault() {
        var t = tracker(300_000);
        t.recordFailure("greenhouse", 1, "e1");
        t.recordFailure("greenhouse", 1, "e2");
        t.recordFailure("greenhouse", 1, "e3");
        assertEquals(JobDiscoveryHealthTracker.CircuitState.OPEN, t.snapshot("greenhouse").circuitState());

        t.reset("greenhouse");
        var s = t.snapshot("greenhouse");
        assertEquals(0, s.totalRuns());
        assertEquals(JobDiscoveryHealthTracker.CircuitState.CLOSED, s.circuitState());
    }

    @Test
    void resetOnUntrackedProviderIsANoOp() {
        var t = tracker(300_000);
        assertDoesNotThrow(() -> t.reset("never-seen"));
    }

    @Test
    void differentProvidersAreTrackedIndependently() {
        var t = tracker(300_000);
        t.recordSuccess("ashby", 100, 5, 5, 0);
        t.recordFailure("smartrecruiters", 200, "err");

        var a = t.snapshot("ashby");
        var s = t.snapshot("smartrecruiters");

        assertEquals(1, a.successRuns());
        assertEquals(0, a.failureRuns());
        assertEquals(0, s.successRuns());
        assertEquals(1, s.failureRuns());
    }

    @Test
    void lastErrorClearedOnNextSuccess() {
        var t = tracker(300_000);
        t.recordFailure("adzuna", 1, "boom");
        assertEquals("boom", t.snapshot("adzuna").lastError());

        t.recordSuccess("adzuna", 1, 1, 1, 0);
        assertNull(t.snapshot("adzuna").lastError());
    }

    @Test
    void zeroLatencyRunsAreRecordedFaithfully() {
        var t = tracker(300_000);
        t.recordSuccess("jooble", 0, 0, 0, 0);
        var s = t.snapshot("jooble");
        assertEquals(0, s.lastLatencyMs());
        assertEquals(0, s.avgLatencyMs());
        assertEquals(0, s.lastJobsFetched());
    }
}
