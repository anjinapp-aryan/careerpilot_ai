package ai.careerpilot.autopilot.api;

import ai.careerpilot.domain.ApplicationDecision;
import ai.careerpilot.domain.ApplicationSubmission;
import ai.careerpilot.repo.ApplicationDecisionRepository;
import ai.careerpilot.repo.ApplicationSubmissionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7 — read-only snapshot of a user's autonomous-agent activity for the Copilot's
 * "Explain Application Decision" skill, so the assistant explains real decisions/submissions instead
 * of hallucinating. Returns empty lists when the agent is dark (no rows exist yet).
 */
@Service
public class AutopilotExplainContextService {

    private static final int RECENT = 10;

    private final ApplicationDecisionRepository decisions;
    private final ApplicationSubmissionRepository submissions;

    public AutopilotExplainContextService(ApplicationDecisionRepository decisions,
                                          ApplicationSubmissionRepository submissions) {
        this.decisions = decisions;
        this.submissions = submissions;
    }

    public AutopilotExplainContext get(UUID userId) {
        List<ApplicationDecision> recentDecisions = decisions.findByUserIdOrderByDecidedAtDesc(userId)
                .stream().limit(RECENT).toList();
        List<ApplicationSubmission> recentSubmissions = submissions.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().limit(RECENT).toList();
        return new AutopilotExplainContext(recentDecisions, recentSubmissions);
    }

    public record AutopilotExplainContext(List<ApplicationDecision> recentDecisions,
                                          List<ApplicationSubmission> recentSubmissions) {}
}
