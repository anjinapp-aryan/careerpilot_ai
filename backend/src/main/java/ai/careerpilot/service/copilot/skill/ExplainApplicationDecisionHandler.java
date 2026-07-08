package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.autopilot.api.AutopilotExplainContextService;
import ai.careerpilot.autopilot.api.AutopilotExplainContextService.AutopilotExplainContext;
import ai.careerpilot.domain.ApplicationDecision;
import ai.careerpilot.domain.ApplicationSubmission;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/**
 * Phase 7 — explains the autonomous agent's application decisions (AUTO_APPLY / HUMAN_REVIEW / SAVE /
 * IGNORE), why a job went to human review, and what the agent actually submitted — grounded in the
 * real {@link ApplicationDecision}/{@link ApplicationSubmission} rows via
 * {@link AutopilotExplainContextService}. The prompt forbids inventing decisions the agent never made.
 */
@Component
public class ExplainApplicationDecisionHandler extends AbstractSkillHandler {

    private final AutopilotExplainContextService autopilotContext;

    public ExplainApplicationDecisionHandler(CareerContextRetriever retriever,
                                             AutopilotExplainContextService autopilotContext) {
        super(retriever);
        this.autopilotContext = autopilotContext;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.EXPLAIN_APPLICATION_DECISION; }

    @Override
    public void assembleContext(SkillContext context) {
        context.autopilot(autopilotContext.get(context.user().userId()));
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, explaining the autonomous application agent to the user.

            TASK — Explain Application Decision: The CONTEXT lists the agent's real recent decisions
            (AUTO_APPLY / HUMAN_REVIEW / SAVE / IGNORE, each with the match score, ATS score, learning
            boost, and a reason) and real submission attempts (provider + status).

            Explain plainly WHY each decision was made from the numbers shown, what HUMAN_REVIEW means
            (the agent needs the user to act — the safe default when a signal is missing or a provider
            can't auto-submit), and that a HUMAN_REVIEW submission was NOT auto-applied. Ground every
            statement in CONTEXT — never invent a decision, score, or submission the agent didn't make.
            If there is no data, say the agent is not enabled yet and explain what turning it on would do.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        AutopilotExplainContext ac = context.autopilot();
        if (ac == null || (ac.recentDecisions().isEmpty() && ac.recentSubmissions().isEmpty())) {
            return "CONTEXT — Application Agent\nNo autonomous decisions or submissions yet (agent disabled or idle).";
        }
        StringBuilder sb = new StringBuilder("CONTEXT — Application Agent\n");
        if (!ac.recentDecisions().isEmpty()) {
            sb.append("\nRecent decisions:\n");
            for (ApplicationDecision d : ac.recentDecisions()) {
                sb.append("- job ").append(d.getJobId()).append(": ").append(d.getOutcome())
                        .append(" (score ").append(d.getMatchScore())
                        .append(d.getAtsScore() != null ? ", ATS " + d.getAtsScore() : "")
                        .append(d.getLearningBoost() != null ? ", learning " + d.getLearningBoost() : "")
                        .append(") — ").append(d.getReason()).append("\n");
            }
        }
        if (!ac.recentSubmissions().isEmpty()) {
            sb.append("\nRecent submission attempts:\n");
            for (ApplicationSubmission s : ac.recentSubmissions()) {
                sb.append("- job ").append(s.getJobId()).append(": ").append(s.getStatus())
                        .append(" via ").append(s.getProvider())
                        .append(s.getReason() != null ? " — " + s.getReason() : "").append("\n");
            }
        }
        return sb.toString();
    }
}
