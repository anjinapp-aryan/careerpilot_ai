package ai.careerpilot.submission.api;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.submission.ApplicationSubmissionSessionService;
import ai.careerpilot.submission.api.dto.ApplicationSubmissionDtos.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Phase 7.16 — the thin façade over {@link ApplicationSubmissionSessionService}: this is what the
 * Apply button calls when {@code application.submission.enabled} is on. Approval/rejection
 * continues to use the EXISTING {@code POST /api/execution/approve|reject/{id}} — no new approval
 * endpoints here, per the no-duplication decision in the Phase 7.16 plan.
 */
@RestController
@RequestMapping("/api/application-submission")
public class ApplicationSubmissionController {

    private final ApplicationSubmissionSessionService service;

    public ApplicationSubmissionController(ApplicationSubmissionSessionService service) {
        this.service = service;
    }

    /** Start a session for the current user — this is what the Apply button calls. */
    @PostMapping
    public SessionResponse start(AuthenticatedUser user, @RequestBody StartRequest request) {
        ApplicationSubmissionSession session = service.start(user.userId(), request.jobId(), request.resumeId())
                .orElseThrow(() -> new IllegalStateException("application submission is not enabled"));
        return SessionResponse.from(session);
    }

    /** The current user's submission sessions, newest first — backs the "My submissions" list. */
    @GetMapping
    public List<SessionResponse> mine(AuthenticatedUser user) {
        return service.recentForUser(user.userId()).stream().map(SessionResponse::from).toList();
    }

    @GetMapping("/{id}")
    public SessionDetailResponse detail(AuthenticatedUser user, @PathVariable UUID id) {
        ApplicationSubmissionSession session = service.find(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("submission session not found"));
        return SessionDetailResponse.from(session, service.answersFor(session.getId()));
    }

    @GetMapping("/{id}/status")
    public StatusResponse status(AuthenticatedUser user, @PathVariable UUID id) {
        ApplicationSubmissionSession session = service.find(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("submission session not found"));
        return StatusResponse.from(session);
    }

    /** Session status transitions for one job's submission history (newest first). */
    @GetMapping("/{id}/history")
    public List<SessionResponse> history(AuthenticatedUser user, @PathVariable UUID id) {
        ApplicationSubmissionSession session = service.find(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("submission session not found"));
        return service.history(user.userId(), session.getJobId()).stream().map(SessionResponse::from).toList();
    }

    /** Sessions currently in WAITING_APPROVAL/SUBMITTING, for the admin/approval queue view. */
    @GetMapping("/queue")
    public List<SessionResponse> queue() {
        return service.queue().stream().map(SessionResponse::from).toList();
    }

    /**
     * Guided Apply — the candidate explicitly confirms they completed the employer's application
     * themselves. Legal only from {@code WAITING_MANUAL_SUBMISSION}; any other status is a 409 via
     * {@link ai.careerpilot.api.GlobalExceptionHandler}'s existing {@code IllegalStateException}
     * mapping, the same convention {@code WorkflowService#resume} uses. There is deliberately no way
     * to reach this transition except this explicit call — never inferred from opening the employer
     * URL, never a timer, never automatic.
     */
    @PostMapping("/{id}/report-submitted")
    public SessionResponse reportSubmitted(AuthenticatedUser user, @PathVariable UUID id,
                                           @RequestBody(required = false) ReportSubmittedRequest request) {
        String note = request == null ? null : request.note();
        ApplicationSubmissionSession session = service.reportUserSubmitted(user.userId(), id, note)
                .orElseThrow(() -> new NoSuchElementException("submission session not found"));
        return SessionResponse.from(session);
    }
}
