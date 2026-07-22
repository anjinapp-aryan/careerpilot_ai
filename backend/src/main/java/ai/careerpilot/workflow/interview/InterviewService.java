package ai.careerpilot.workflow.interview;

import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.InterviewFeedback;
import ai.careerpilot.domain.InterviewTimeline;
import ai.careerpilot.memory.CareerMemoryService;
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
    private final CareerMemoryService careerMemory;
    private final boolean enabled;

    public InterviewService(InterviewRepository interviews, InterviewFeedbackRepository feedback,
                            InterviewTimelineRepository timeline, InterviewMetrics metrics,
                            CareerMemoryService careerMemory,
                            @Value("${interview.tracking.enabled:false}") boolean enabled) {
        this.interviews = interviews;
        this.feedback = feedback;
        this.timeline = timeline;
        this.metrics = metrics;
        this.careerMemory = careerMemory;
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

    /** Feedback ratings at or above this are treated as a real signal the round went well (Phase 7.17.2). */
    private static final int RESULT_FROM_RATING_PASS_THRESHOLD = 4;
    /** Feedback ratings at or below this are treated as a real signal the round did not go well. */
    private static final int RESULT_FROM_RATING_FAIL_THRESHOLD = 2;

    /**
     * Attach feedback to an interview round. Empty when disabled/failed. Never throws.
     *
     * <p>Phase 7.17.2 — closes the data gap that made {@code Interview.result} (PASSED/FAILED)
     * permanently uncomputable: nothing else in this codebase has any signal about how an interview
     * actually went, but a candidate rating their own interview experience IS a real, deterministic
     * signal. A rating &ge; {@value #RESULT_FROM_RATING_PASS_THRESHOLD} sets the round's {@code
     * result} to PASSED; &le; {@value #RESULT_FROM_RATING_FAIL_THRESHOLD} sets FAILED; a mid-range
     * rating (3) is genuinely ambiguous and leaves the existing result untouched rather than
     * guessing. This is the candidate's self-assessment, not a confirmed employer decision — company
     * interview intelligence built on top of this must say so.  Never overwrites a terminal {@code
     * CANCELLED} result, and never runs when {@code rating} is null (nothing to infer from).
     */
    @Transactional
    public Optional<InterviewFeedback> addFeedback(UUID interviewId, String text, Integer rating) {
        if (!enabled) return Optional.empty();
        try {
            InterviewFeedback saved = feedback.save(InterviewFeedback.builder()
                    .interviewId(interviewId).feedback(text).rating(rating).build());
            Interview interview = interviews.findById(interviewId).orElse(null);
            if (interview != null && rating != null && !Interview.RESULT_CANCELLED.equals(interview.getResult())) {
                String inferredResult = rating >= RESULT_FROM_RATING_PASS_THRESHOLD ? Interview.RESULT_PASSED
                        : rating <= RESULT_FROM_RATING_FAIL_THRESHOLD ? Interview.RESULT_FAILED
                        : null; // ambiguous mid-range rating — leave result untouched, never guess
                if (inferredResult != null && !inferredResult.equals(interview.getResult())) {
                    interview.setResult(inferredResult);
                    interviews.save(interview);
                }
            }
            // Additive Phase 7.15.1 side effect — never blocks or fails the feedback save.
            try {
                if (interview != null) {
                    careerMemory.record(
                            interview.getUserId(), "INTERVIEW_FEEDBACK", "INTERVIEW",
                            interview.getInterviewType(), text, java.math.BigDecimal.ONE,
                            "INTERVIEW_INTELLIGENCE", rating != null && rating <= 2 ? 4 : 3, false,
                            interview.getJobId(), null, null, null);
                }
            } catch (Exception e) {
                log.debug("Career memory capture failed for interview feedback {}: {}", interviewId, e.toString());
            }
            return Optional.of(saved);
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
