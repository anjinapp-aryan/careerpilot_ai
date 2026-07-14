package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.applications.ApplicationCardService;
import ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse;
import ai.careerpilot.domain.Application;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Applications Page — AI Job Application Command Center. Copilot skill: "why is this application
 * stuck", "how do I improve my chances", "should I follow up", "generate a follow-up/outreach
 * email", "predict my interview chance", "prepare for interview" (deep-links StoryRecommendationPanel
 * client-side), "compare against job description" (reuses ATS_ANALYSIS/gap-analysis data — routed
 * separately, not duplicated here).
 *
 * <p>Reads the SAME deterministic {@link ApplicationCardService} output the Applications page cards
 * use — the LLM only narrates it, it never re-derives health/recommendation/next-action scores.
 * {@code context.contextId()} is expected to be the application id (frontend sends it when opening
 * the drawer on this page).
 */
@Component
public class ExplainApplicationStatusHandler extends AbstractSkillHandler {

    private final ApplicationRepository applications;
    private final ApplicationCardService cardService;

    public ExplainApplicationStatusHandler(CareerContextRetriever retriever, ApplicationRepository applications,
                                           ApplicationCardService cardService) {
        super(retriever);
        this.applications = applications;
        this.cardService = cardService;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.EXPLAIN_APPLICATION_STATUS; }

    @Override
    public void assembleContext(SkillContext context) {
        ApplicationCardResponse card = null;
        String idStr = context.contextId();
        if (idStr != null && !idStr.isBlank()) {
            try {
                UUID id = UUID.fromString(idStr);
                Application app = applications.findById(id).orElse(null);
                if (app != null && app.getUserId().equals(context.user().userId())) {
                    card = cardService.assemble(context.user().userId(), app);
                }
            } catch (IllegalArgumentException ignored) {
                // contextId wasn't a UUID — fall through with no card, general-assistant-style reply.
            }
        }
        context.applicationCommandCenter(card);
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, a job-search strategy coach embedded on the Applications page.

            TASK — Explain Application Status: Using the deterministic health/recommendation/next-action
            signals already computed in CONTEXT (never invent your own score), answer the user's question
            in plain language:
            - "Why is this stuck?" → explain using the health reasoning and days since last change.
            - "How do I improve my chances?" → turn the recommendation reasoning into 2-3 concrete actions.
            - "Should I follow up?" → answer directly based on the recommendation action.
            - "Generate a follow-up email" / "Generate an outreach email" → draft a short, professional
              email (3-5 sentences) referencing the real job title/company from CONTEXT. Do not fabricate
              a recruiter name or contact — address it generically ("Hiring Team" / "Hello,").
            - "Predict my interview chance" → report the recommendation/health signals honestly; if the
              application has very few real data points, say confidence is low rather than inventing a
              precise percentage.
            - "Prepare for interview" → suggest the user open the Interview Readiness panel / STAR Story
              tools on this page; do not attempt to generate interview content yourself here.

            Never mention a recruiter, LinkedIn message, ghosting risk, or salary prediction — none of
            that data exists in this system.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        ApplicationCardResponse card = context.applicationCommandCenter();
        if (card == null) {
            return "No specific application is in focus — answer generally about job-application strategy.";
        }
        return "CONTEXT — Application\n"
                + "Job: " + nullSafe(card.jobTitle()) + " @ " + nullSafe(card.company()) + "\n"
                + "Status: " + nullSafe(card.status()) + (card.lifecycleStatus() != null ? " (lifecycle: " + card.lifecycleStatus() + ")" : "") + "\n"
                + "Match score: " + orNa(card.matchScore()) + " · ATS score: " + orNa(card.atsScore()) + "\n"
                + "Health: " + card.healthStatus() + " (score " + card.healthScore() + ") — " + nullSafe(card.healthReasoning()) + "\n"
                + "Recommendation: " + card.recommendationAction() + " — " + nullSafe(card.recommendationReasoning()) + "\n"
                + "Suggested next action: " + nullSafe(card.suggestedNextAction())
                + (card.suggestedNextActionAt() != null ? " (by " + card.suggestedNextActionAt() + ")" : "") + "\n"
                + "Resume tailored: " + card.resumeTailored() + " · ATS analysis: " + card.atsAnalysisReady()
                + " · Cover letter: " + card.coverLetterReady() + " · Package: " + card.applicationPackageReady()
                + " · Review: " + card.applicationReviewReady();
    }
}
