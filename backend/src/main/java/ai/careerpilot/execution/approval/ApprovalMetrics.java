package ai.careerpilot.execution.approval;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 2E.4 — in-memory counters for the human approval workflow. */
@Component
public class ApprovalMetrics {

    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong approved = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();

    public void recordEnqueued() { enqueued.incrementAndGet(); }
    public void recordApproved() { approved.incrementAndGet(); }
    public void recordRejected() { rejected.incrementAndGet(); }
    public void recordExpired() { expired.incrementAndGet(); }
    public void recordConflict() { conflicts.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalEnqueued", enqueued.get());
        out.put("approvalApproved", approved.get());
        out.put("approvalRejected", rejected.get());
        out.put("approvalExpired", expired.get());
        out.put("approvalConflicts", conflicts.get());
        return out;
    }
}
