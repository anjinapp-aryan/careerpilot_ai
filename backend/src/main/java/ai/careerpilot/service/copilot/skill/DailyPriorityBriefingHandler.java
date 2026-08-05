package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.CareerContextService;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/**
 * Phase 11B — the flagship cross-system query: "what should I do today / what's blocking me /
 * what's my top priority". Unlike every other handler, this one's own {@link #contextBlock} is
 * deliberately thin — the real cross-subsystem data (Mission, Timeline, Workflow, Applications,
 * Interviews, Companies) plus the deterministic, priority-ordered recommended-actions list are
 * rendered by {@link SkillContext#careerContextBlock()}, which {@code CopilotService} appends
 * after every handler's own block. This handler populates that block itself in {@link
 * #assembleContext} (via {@link CareerContextService}, not a new query — the exact same
 * aggregator Phase 11A wired centrally) so a real, actionable answer is available here even when
 * the global {@code copilot.career-context.enabled} flag is off; if that flag is also on, {@code
 * CopilotService} refetches the same aggregate once more — a harmless, bounded extra call, not a
 * correctness issue.
 */
@Component
public class DailyPriorityBriefingHandler extends AbstractSkillHandler {

    private final CareerContextService careerContextService;

    public DailyPriorityBriefingHandler(CareerContextRetriever retriever, CareerContextService careerContextService) {
        super(retriever);
        this.careerContextService = careerContextService;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.DAILY_PRIORITY_BRIEFING; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            AuthenticatedUser user = context.user();
            context.careerContext(careerContextService.getCareerContext(user));
        } catch (Exception e) {
            log.warn("Could not assemble daily priority briefing context: {}", e.getMessage());
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, acting as the user's daily career operating assistant.

            TASK — Daily Priority Briefing: The user is asking what to focus on right now (today,
            this week, or "what's blocking me"). The CAREER CONTEXT section below already contains
            a deterministic, priority-ordered RECOMMENDED ACTIONS list computed from verified data
            across Mission, Applications, Workflow runs, Interviews, and Company Intelligence.

            Present that list as your primary answer, in the given priority order, in your own
            words — do not just copy it verbatim, but do not reorder it or add actions that are not
            in it. If the list is empty, say so plainly: there is nothing urgent right now, and
            optionally mention what you do see in the context (e.g. an active mission, an
            in-progress workflow) as lower-stakes context, never as a fabricated priority.

            If the CAREER CONTEXT section is entirely missing or empty, say exactly:
            "I don't currently have verified information to build a priority briefing." Do not guess.

            Never invent an application, interview, company, or workflow that isn't in the context.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        return "CONTEXT — Daily Priority Briefing\n"
                + "See the CAREER CONTEXT section below for verified cross-system data and the "
                + "deterministic, priority-ordered recommended actions.\n";
    }
}
