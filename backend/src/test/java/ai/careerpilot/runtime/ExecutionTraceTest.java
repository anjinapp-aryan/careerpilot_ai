package ai.careerpilot.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTraceTest {

    @Test
    void recordsEventsInOrderAndTracksStartAndEndTimes() {
        ExecutionTrace trace = new ExecutionTrace();

        trace.start();
        trace.record(ExecutionEvent.info("A", "first"));
        trace.record(ExecutionEvent.warn("B", "second"));
        trace.end();

        assertThat(trace.startTime()).isNotNull();
        assertThat(trace.endTime()).isNotNull();
        assertThat(trace.events()).extracting("phase").containsExactly("A", "B");
    }

    @Test
    void eventsListIsAnImmutableSnapshot() {
        ExecutionTrace trace = new ExecutionTrace();
        trace.record(ExecutionEvent.info("A", "first"));

        java.util.List<ExecutionEvent> snapshot = trace.events();
        trace.record(ExecutionEvent.info("B", "second"));

        assertThat(snapshot).hasSize(1);
        assertThat(trace.events()).hasSize(2);
    }
}
