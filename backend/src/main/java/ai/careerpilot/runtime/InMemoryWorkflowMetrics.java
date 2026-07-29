package ai.careerpilot.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LangGraph Workflow Runtime — the only {@link WorkflowMetrics}. Plain {@link AtomicLong} counters
 * per {@link WorkflowExecutionStatus}, plus a per-workflowId total — bounded by the number of
 * distinct workflow ids/statuses ever seen, not by execution volume, so it stays resource-cheap on
 * the 2GB-RAM Oracle Cloud Free Tier VM even under sustained load.
 */
public class InMemoryWorkflowMetrics implements WorkflowMetrics {

    private final Map<WorkflowExecutionStatus, AtomicLong> byStatus = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> byWorkflowId = new ConcurrentHashMap<>();
    private final AtomicLong totalDurationMillis = new AtomicLong();
    private final AtomicLong totalExecutions = new AtomicLong();
    private final AtomicReference<Instant> lastExecutionAt = new AtomicReference<>();

    @Override
    public void record(WorkflowExecutionResult result) {
        byStatus.computeIfAbsent(result.executionStatus(), s -> new AtomicLong()).incrementAndGet();
        byWorkflowId.computeIfAbsent(result.workflowId(), id -> new AtomicLong()).incrementAndGet();
        totalDurationMillis.addAndGet(result.duration() == null ? 0 : result.duration().toMillis());
        totalExecutions.incrementAndGet();
        lastExecutionAt.set(result.endTime());
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new ConcurrentHashMap<>();
        long total = totalExecutions.get();
        snapshot.put("totalExecutions", total);
        snapshot.put("averageDurationMillis", total == 0 ? 0L : totalDurationMillis.get() / total);
        Instant last = lastExecutionAt.get();
        if (last != null) {
            snapshot.put("lastExecutionAt", last);
        }
        byStatus.forEach((status, count) -> snapshot.put("status." + status.name(), count.get()));
        byWorkflowId.forEach((id, count) -> snapshot.put("workflow." + id, count.get()));
        return snapshot;
    }
}
