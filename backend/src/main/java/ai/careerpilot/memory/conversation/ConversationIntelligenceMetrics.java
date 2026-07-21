package ai.careerpilot.memory.conversation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 7.15.2 — in-memory counters, same shape as {@code CareerMemoryMetrics}/{@code InterviewMetrics}. */
@Component
public class ConversationIntelligenceMetrics {

    private final AtomicLong conversationsAnalyzed = new AtomicLong();
    private final AtomicLong decisionsDetected = new AtomicLong();
    private final AtomicLong memoriesAccepted = new AtomicLong();
    private final AtomicLong rejectedLowConfidence = new AtomicLong();
    private final AtomicLong duplicatesSkipped = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong confidenceSumHundredths = new AtomicLong(); // confidence * 100, summed over decisionsDetected
    private final AtomicLong processingLatencyTotalNanos = new AtomicLong();

    public void recordConversationAnalyzed() { conversationsAnalyzed.incrementAndGet(); }

    public void recordDecisionDetected(double confidence) {
        decisionsDetected.incrementAndGet();
        confidenceSumHundredths.addAndGet(Math.round(confidence * 100));
    }

    public void recordAccepted() { memoriesAccepted.incrementAndGet(); }
    public void recordRejectedLowConfidence() { rejectedLowConfidence.incrementAndGet(); }
    public void recordDuplicateSkipped() { duplicatesSkipped.incrementAndGet(); }
    public void recordWriteFailure() { writeFailures.incrementAndGet(); }
    public void recordProcessingLatency(long durationNanos) { processingLatencyTotalNanos.addAndGet(durationNanos); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        long conversations = conversationsAnalyzed.get();
        long decisions = decisionsDetected.get();
        long accepted = memoriesAccepted.get();
        out.put("conversationsAnalyzed", conversations);
        out.put("decisionsDetected", decisions);
        out.put("memoriesAccepted", accepted);
        out.put("rejectedLowConfidence", rejectedLowConfidence.get());
        out.put("duplicatesSkipped", duplicatesSkipped.get());
        out.put("writeFailures", writeFailures.get());
        out.put("averageConfidence", decisions == 0 ? 0.0 : (confidenceSumHundredths.get() / 100.0) / decisions);
        out.put("avgProcessingLatencyMs", conversations == 0 ? 0.0
                : (processingLatencyTotalNanos.get() / (double) conversations) / 1_000_000.0);
        out.put("memoryWriteSuccessRate", decisions == 0 ? 1.0 : (double) accepted / decisions);
        return out;
    }
}
