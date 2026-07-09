package ai.careerpilot.workflow.correlation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 3A.0 — in-memory counters for the correlation + dead-letter framework. Two logically distinct
 * signal groups (correlation lifecycle vs dead-letter captures) exposed through one component so the
 * two infra diagnostics endpoints can read a coherent snapshot.
 */
@Component
public class WorkflowMetrics {

    private final AtomicLong correlationsStarted = new AtomicLong();
    private final AtomicLong correlationsAdvanced = new AtomicLong();
    private final AtomicLong correlationFailures = new AtomicLong();
    private final AtomicLong deadLettersRecorded = new AtomicLong();
    private final AtomicLong deadLetterFailures = new AtomicLong();

    public void recordCorrelationStart() { correlationsStarted.incrementAndGet(); }
    public void recordCorrelationAdvance() { correlationsAdvanced.incrementAndGet(); }
    public void recordCorrelationFailure() { correlationFailures.incrementAndGet(); }
    public void recordDeadLetter() { deadLettersRecorded.incrementAndGet(); }
    public void recordDeadLetterFailure() { deadLetterFailures.incrementAndGet(); }

    public Map<String, Object> correlationSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("correlationsStarted", correlationsStarted.get());
        out.put("correlationsAdvanced", correlationsAdvanced.get());
        out.put("correlationFailures", correlationFailures.get());
        return out;
    }

    public Map<String, Object> deadLetterSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deadLettersRecorded", deadLettersRecorded.get());
        out.put("deadLetterFailures", deadLetterFailures.get());
        return out;
    }
}
