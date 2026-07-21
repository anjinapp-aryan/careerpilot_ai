package ai.careerpilot.workflow.interview.api;

import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.InterviewFeedback;
import ai.careerpilot.repo.InterviewFeedbackRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 7.15.3A — {@code InterviewService} (Phase 3A.4) has existed since early in this codebase
 * with real recording/feedback logic, but had NO REST controller at all — this is the first
 * frontend-facing exposure, not a duplicate of anything. Read-only; no new service logic, only
 * {@link InterviewRepository}/{@link InterviewFeedbackRepository} queries already available.
 * Per-user, JWT-authenticated (same convention as {@code OfferController}/{@code CareerMemoryController}).
 * Degrades to an empty list, not an error, when {@code interview.tracking.enabled} is off.
 */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewRepository interviews;
    private final InterviewFeedbackRepository feedback;
    private final boolean enabled;

    public InterviewController(InterviewRepository interviews, InterviewFeedbackRepository feedback,
                               @Value("${interview.tracking.enabled:false}") boolean enabled) {
        this.interviews = interviews;
        this.feedback = feedback;
        this.enabled = enabled;
    }

    public record InterviewFeedbackDto(String feedback, Integer rating, Instant createdAt) {}

    public record InterviewDto(UUID id, UUID jobId, String interviewType, String interviewer,
                               Integer durationMinutes, String result, Instant scheduledAt,
                               Instant createdAt, List<InterviewFeedbackDto> feedback) {}

    @GetMapping
    public List<InterviewDto> list(AuthenticatedUser user) {
        if (!enabled) return List.of();
        List<Interview> rows = interviews.findByUserIdOrderByCreatedAtDesc(user.userId());
        if (rows.isEmpty()) return List.of();

        List<UUID> ids = rows.stream().map(Interview::getId).toList();
        Map<UUID, List<InterviewFeedbackDto>> feedbackByInterview = feedback.findByInterviewIdIn(ids).stream()
                .collect(Collectors.groupingBy(InterviewFeedback::getInterviewId,
                        Collectors.mapping(f -> new InterviewFeedbackDto(f.getFeedback(), f.getRating(), f.getCreatedAt()),
                                Collectors.toList())));

        return rows.stream()
                .map(i -> new InterviewDto(i.getId(), i.getJobId(), i.getInterviewType(), i.getInterviewer(),
                        i.getDurationMinutes(), i.getResult(), i.getScheduledAt(), i.getCreatedAt(),
                        feedbackByInterview.getOrDefault(i.getId(), List.of())))
                .toList();
    }
}
