package ai.careerpilot.dailydiscovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DailyJobDiscoveryMetricsTest {

    @Test
    void neverRunReportsZeroCounters() {
        var m = new DailyJobDiscoveryMetrics();
        var snap = m.snapshot();
        assertEquals(0L, snap.get("totalRuns"));
        assertEquals("NEVER_RUN", snap.get("lastStatus"));
        assertNull(snap.get("lastRunAt"));
    }

    @Test
    void successfulRunUpdatesCounters() {
        var m = new DailyJobDiscoveryMetrics();
        m.recordRunStart();
        m.recordRunFinished("SUCCESS", 1234L, 7);
        var snap = m.snapshot();
        assertEquals(1L, snap.get("totalRuns"));
        assertEquals(1L, snap.get("successfulRuns"));
        assertEquals(0L, snap.get("failedRuns"));
        assertEquals(7L, snap.get("usersProcessed"));
        assertEquals("SUCCESS", snap.get("lastStatus"));
        assertEquals(1234L, snap.get("lastDurationMs"));
        assertNotNull(snap.get("lastSuccessAt"));
    }

    @Test
    void failedRunIncrementsFailedRunsNotSuccess() {
        var m = new DailyJobDiscoveryMetrics();
        m.recordRunStart();
        m.recordRunFinished("FAILED", 500L, 0);
        var snap = m.snapshot();
        assertEquals(1L, snap.get("failedRuns"));
        assertEquals(0L, snap.get("successfulRuns"));
        assertNull(snap.get("lastSuccessAt"));
    }
}
