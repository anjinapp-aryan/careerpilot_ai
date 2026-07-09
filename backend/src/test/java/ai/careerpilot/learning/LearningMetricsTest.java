package ai.careerpilot.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LearningMetricsTest {

    @Test
    void unseenStageReportsZero() {
        var m = new LearningMetrics();
        assertEquals(0, m.total("EVENT_CAPTURE"));
        assertEquals(0, m.failures("EVENT_CAPTURE"));
    }

    @Test
    void recordSuccessIncrementsTotalOnly() {
        var m = new LearningMetrics();
        m.recordSuccess("EVENT_CAPTURE");
        m.recordSuccess("EVENT_CAPTURE");
        assertEquals(2, m.total("EVENT_CAPTURE"));
        assertEquals(0, m.failures("EVENT_CAPTURE"));
    }

    @Test
    void recordFailureIncrementsBoth() {
        var m = new LearningMetrics();
        m.recordFailure("SUCCESS_PATTERN");
        assertEquals(1, m.total("SUCCESS_PATTERN"));
        assertEquals(1, m.failures("SUCCESS_PATTERN"));
    }

    @Test
    void stagesAreIndependent() {
        var m = new LearningMetrics();
        m.recordSuccess("SUCCESS_PATTERN");
        m.recordFailure("FAILURE_PATTERN");
        assertEquals(1, m.total("SUCCESS_PATTERN"));
        assertEquals(0, m.failures("SUCCESS_PATTERN"));
        assertEquals(1, m.total("FAILURE_PATTERN"));
        assertEquals(1, m.failures("FAILURE_PATTERN"));
    }

    @Test
    void snapshotIncludesBothCounters() {
        var m = new LearningMetrics();
        m.recordSuccess("EVENT_CAPTURE");
        var snap = m.snapshot("EVENT_CAPTURE");
        assertEquals(1L, snap.get("EVENT_CAPTURETotal"));
        assertEquals(0L, snap.get("EVENT_CAPTUREFailures"));
    }
}
