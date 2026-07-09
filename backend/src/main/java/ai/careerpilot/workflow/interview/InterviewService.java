package ai.careerpilot.workflow.interview;

import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.InterviewFeedback;
import ai.careerpilot.domain.InterviewTimeline;
import ai.careerpilot.repo.InterviewFeedbackRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.InterviewTimelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3A.4 — records interview rounds and their feedback/timeline. Flag-gated dark by
 * {@code interview.tracking.enabled}; append-only; never throws.
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private final InterviewRepository interviews;
    private final InterviewFeedbackRepository feedback;
    private final InterviewTimelineRepository timeline;
    private final InterviewMetrics metrics;
    private final boolean enabled;

    public InterviewService(InterviewRepository interviews, InterviewFeedbackRepository feedback,
                            InterviewTimelineRepository timeline, InterviewMetrics metrics,
                            @Value("${interview.tracking.enabled:false}") boolean enabled) {
        this.interviews = interviews;
        this.feedback = feedback;
        this.timeline = timeline;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Record a new interview round (default result SCHEDULED). Empty when disabled/failed. Never throws. */
    @Transactional
    public Optional<Interview> record(UUID userId, UUID jobId, String interviewType,
                                      String interviewer, Integer durationMinutes, String result) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            Interview row = interviews.save(Interview.builder()
                    .userId(userId).jobId(jobId)
                    .interviewType(interviewType).interviewer(interviewer)
                    .durationMinutes(durationMinutes)
                    .result(result == null ? Interview.RESULT_SCHEDULED : result)
                    .scheduledAt(Instant.now())
                    .build());
            timeline.save(InterviewTimeline.builder()
                    .interviewId(row.getId()).eventType("RECORDED")
                    .details(interviewType + " / " + row.getResult()).build());
            metrics.recordRecorded();
            log.info("INTERVIEW recorded user={} job={} type={}", userId, jobId, interviewType);
            return Optional.of(row);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("INTERVIEW record error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    /** Attach feedback to an interview round. Empty when disabled/failed. Never throws. */
    @Transactional
    public Optional<InterviewFeedback> addFeedback(UUID interviewId, String text, Integer rating) {
        if (!enabled) return Optional.empty();
        try {
            return Optional.of(feedback.save(InterviewFeedback.builder()
                    .interviewId(interviewId).feedback(text).rating(rating).build()));
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("INTERVIEW feedback error interview={}: {}", interviewId, e.toString());
            return Optional.empty();
        }
    }

    public List<Interview> forJob(UUID userId, UUID jobId) {
        return interviews.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    /** Called by the detection worker to count a detected interview signal (no persistence side effect). */
    public void markDetected() {
        metrics.recordDetected();
    }
}
