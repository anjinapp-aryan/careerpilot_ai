package ai.careerpilot.story.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoryMetricsTest {

    @Test
    void incrementAccumulatesPerKey() {
        StoryMetrics metrics = new StoryMetrics();
        metrics.increment("storiesGenerated");
        metrics.increment("storiesGenerated");
        metrics.increment("storiesCreated");
        assertEquals(2, metrics.get("storiesGenerated"));
        assertEquals(1, metrics.get("storiesCreated"));
    }

    @Test
    void unknownKeyReturnsZero() {
        assertEquals(0, new StoryMetrics().get("neverIncremented"));
    }

    @Test
    void snapshotPrefixesCounterKeys() {
        StoryMetrics metrics = new StoryMetrics();
        metrics.increment("usageRecorded");
        assertEquals(1L, metrics.snapshot().get("counter.usageRecorded"));
    }
}
