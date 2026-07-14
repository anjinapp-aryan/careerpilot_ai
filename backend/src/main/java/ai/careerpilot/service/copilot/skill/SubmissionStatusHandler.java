package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.submission.api.SubmissionCopilotContextService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 7.16 — Copilot skill: "what's the status of my application submission", "why is it
 * waiting on human approval", "why did submission fail", "what submission strategy was used".
 * Read-only over the user's recent {@code ApplicationSubmissionSession} rows — grounded strictly
 * in that data, same no-hallucination discipline as every other skill handler (see CLAUDE.md).
 * Handles both {@link CopilotSkill#SUBMISSION_STATUS} and {@link
 * CopilotSkill#EXPLAIN_SUBMISSION_STRATEGY} — the two skills share the same context and system
 * prompt style; the user's message text disambiguates intent for the model.
 */
@Component
public class SubmissionStatusHandler extends AbstractSkillHandler {

    private final SubmissionCopilotContextService submissionContext;

    public SubmissionStatusHandler(CareerContextRetriever retriever, SubmissionCopilotContextService submissionContext) {
        super(retriever);
        this.submissionContext = submissionContext;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.SUBMISSION_STATUS; }

    @Override
    public void assembleContext(SkillContext context) {
        context.submission(submissionContext.forUser(context.user().userId()));
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, explaining the Application Submission Pipeline to the user.

            TASK — Submission Status / Strategy: Using ONLY the session data in CONTEXT below, answer
            the user's question about their application submission(s): current status, why a session
            is waiting on human approval, why a session failed (use its failureReason verbatim when
            present), or which submission strategy/provider was resolved for a job. Never invent a
            confirmation number, submission outcome, or provider that isn't in CONTEXT. If the
            pipeline is not enabled for this account, say so plainly and explain that applying still
            works via the standard Apply flow.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var submission = context.submission();
        if (submission == null || !submission.enabled()) {
            return "The Application Submission Pipeline is not enabled for this account.";
        }
        List<ApplicationSubmissionSession> sessions = submission.recentSessions();
        if (sessions.isEmpty()) {
            return "The user has no application submission sessions yet.";
        }
        String rendered = sessions.stream().map(this::renderSession).collect(Collectors.joining("\n---\n"));
        return "CONTEXT — Recent Application Submission Sessions (" + sessions.size() + ")\n" + rendered;
    }

    private String renderSession(ApplicationSubmissionSession s) {
        return "Session: " + s.getId()
                + "\nJob: " + s.getJobId()
                + "\nStatus: " + nullSafe(s.getStatus())
                + "\nProvider: " + nullSafe(s.getProvider())
                + "\nFailure Reason: " + nullSafe(s.getFailureReason())
                + "\nStarted: " + (s.getStartedAt() == null ? "n/a" : s.getStartedAt())
                + "\nCompleted: " + (s.getCompletedAt() == null ? "n/a" : s.getCompletedAt());
    }
}
