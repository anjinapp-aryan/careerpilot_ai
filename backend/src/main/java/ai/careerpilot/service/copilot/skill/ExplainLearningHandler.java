package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.learning.api.LearningExplainContextService;
import ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/**
 * Phase 6.5 — Part 11 (AI Copilot). Explains the Learning Engine itself: why a job's recommendation
 * score was boosted/penalized, which resume version performs best, and what the learned career
 * probabilities are. Every fact comes from {@link LearningExplainContextService}, which reads
 * straight from the Phase 6 tables — the prompt explicitly forbids fabricating numbers when the
 * context is empty (learning disabled or no history yet).
 */
@Component
public class ExplainLearningHandler extends AbstractSkillHandler {

    private final LearningExplainContextService learningContext;

    public ExplainLearningHandler(CareerContextRetriever retriever, LearningExplainContextService learningContext) {
        super(retriever);
        this.learningContext = learningContext;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.EXPLAIN_LEARNING; }

    @Override
    public void assembleContext(SkillContext context) {
        context.learning(learningContext.get(context.user().userId()));
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, explaining the platform's self-learning career intelligence
            engine to the user.

            TASK — Explain Learning: The CONTEXT contains the user's real learned data: success/failure
            patterns by company/role/skill/location/industry/salary band, learned recommendation
            boosts/penalties, their best-performing resume version, and learned career probabilities.

            Ground every claim in the numbers shown in CONTEXT — never invent a company name, rate, or
            score that isn't there. If the Learning Engine is disabled or has no data yet for this user,
            say so plainly and explain what activity (applications, interviews, offers) would start
            building it, rather than inventing plausible-sounding numbers.

            Explain WHY a pattern exists in plain language (e.g. "You've had a 40% offer rate at
            fintech companies across 12 applications, so fintech roles get a recommendation boost")
            rather than just repeating raw numbers.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        LearningExplainContext lc = context.learning();
        if (lc == null || (lc.totalEvents() == 0 && lc.topSuccessPatterns().isEmpty()
                && lc.topFailurePatterns().isEmpty() && lc.careerStrategy() == null)) {
            return "CONTEXT — Learning Engine\nNo learning data available yet (engine disabled or no history).";
        }

        StringBuilder sb = new StringBuilder("CONTEXT — Learning Engine\n");
        sb.append("Total learning events recorded: ").append(lc.totalEvents()).append("\n");

        if (!lc.topSuccessPatterns().isEmpty()) {
            sb.append("\nTop success patterns:\n");
            for (SuccessPattern p : lc.topSuccessPatterns()) {
                sb.append("- ").append(p.getDimension()).append(" \"").append(p.getDimensionKey()).append("\": ")
                        .append(p.getApplications()).append(" applications, ").append(p.getInterviews())
                        .append(" interviews, ").append(p.getOffers()).append(" offers, success rate ")
                        .append(p.getSuccessRate()).append("\n");
            }
        }
        if (!lc.topFailurePatterns().isEmpty()) {
            sb.append("\nTop failure patterns:\n");
            for (FailurePattern p : lc.topFailurePatterns()) {
                sb.append("- ").append(p.getDimension()).append(" \"").append(p.getDimensionKey()).append("\": ")
                        .append(p.getApplications()).append(" applications, ").append(p.getResponses())
                        .append(" responses, failure rate ").append(p.getFailureRate())
                        .append(" (recommended penalty ").append(p.getRecommendedPenalty()).append(")\n");
            }
        }
        if (!lc.topRecommendationWeights().isEmpty()) {
            sb.append("\nLearned recommendation boosts/penalties:\n");
            for (RecommendationWeight w : lc.topRecommendationWeights()) {
                sb.append("- ").append(w.getDimension()).append(" \"").append(w.getDimensionKey()).append("\": ")
                        .append(w.getBoost() >= 0 ? "+" : "").append(w.getBoost()).append("\n");
            }
        }
        if (lc.bestResumeVersion() != null) {
            var rv = lc.bestResumeVersion();
            sb.append("\nBest-performing resume version: ").append(rv.getResumeVersion())
                    .append(" (").append(rv.getApplications()).append(" applications, offer rate ")
                    .append(rv.getOfferRate()).append(")\n");
        }
        if (lc.careerStrategy() != null) {
            var cs = lc.careerStrategy();
            sb.append("\nLearned career probabilities: career success ").append(cs.getCareerSuccessProbability())
                    .append(", interview ").append(cs.getInterviewProbability())
                    .append(", offer ").append(cs.getOfferProbability())
                    .append(", growth ").append(cs.getCareerGrowthProbability())
                    .append(", market demand ").append(cs.getMarketDemandScore()).append("\n");
            if (cs.getRecommendedTrajectory() != null) {
                sb.append("Recommended trajectory: ").append(cs.getRecommendedTrajectory()).append("\n");
            }
        }
        return sb.toString();
    }
}
