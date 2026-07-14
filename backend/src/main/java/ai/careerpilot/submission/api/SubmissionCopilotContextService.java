package ai.careerpilot.submission.api;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import ai.careerpilot.submission.ApplicationSubmissionSessionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.16 — supplies a {@code SkillContext}-shaped context block for Copilot integration,
 * mirroring {@code StoryCopilotContextService}. Read-only; never writes.
 */
@Service
public class SubmissionCopilotContextService {

    private final ApplicationSubmissionSessionService service;
    private final ApplicationSubmissionSessionRepository sessions;

    public SubmissionCopilotContextService(ApplicationSubmissionSessionService service,
                                           ApplicationSubmissionSessionRepository sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    public record SubmissionCopilotContext(boolean enabled, List<ApplicationSubmissionSession> recentSessions) {}

    public SubmissionCopilotContext forUser(UUID userId) {
        if (!service.isEnabled()) return new SubmissionCopilotContext(false, List.of());
        List<ApplicationSubmissionSession> recent = sessions.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().limit(10).toList();
        return new SubmissionCopilotContext(true, recent);
    }
}
