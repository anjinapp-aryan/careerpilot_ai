package ai.careerpilot.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * LangGraph Workflow Runtime — a single execution's append-only event/timing accumulator. One
 * instance is created per {@link WorkflowExecutionRequest} by {@link WorkflowLifecycleManager} and
 * discarded once mapped into a {@link WorkflowExecutionResult} — this is NOT a history store (that
 * already exists, deliberately unduplicated, as {@code ai.careerpilot.missionexecution.ExecutionHistory}).
 * Not thread-shared: one trace belongs to exactly one in-flight execution.
 */
public final class ExecutionTrace {

    private final List<ExecutionEvent> events = new ArrayList<>();
    private Instant startTime;
    private Instant endTime;

    public void start() {
        this.startTime = Instant.now();
    }

    public void end() {
        this.endTime = Instant.now();
    }

    public void record(ExecutionEvent event) {
        events.add(event);
    }

    public List<ExecutionEvent> events() {
        return List.copyOf(events);
    }

    public Instant startTime() {
        return startTime;
    }

    public Instant endTime() {
        return endTime;
    }
}
