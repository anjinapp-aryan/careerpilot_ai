package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.domain.ApplicationReview;
import ai.careerpilot.review.api.ReviewExplainContextService;
import ai.careerpilot.review.api.ReviewExplainContextService.ReviewExplainContext;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/**
 * Phase 7.12 — explains the AI Review Pipeline: the resume / ATS / company-fit / learning reviewer
 * scores, the consistency verdict, the overall quality (score + category), and the final verdict
 * (READY / HUMAN_REVIEW / BLOCKED). Grounded in the real {@link ApplicationReview} rows via
 * {@link ReviewExplainContextService}; the prompt forbids inventing scores or verdicts the pipeline
 * never produced.
 */
@Component
public class ExplainApplicationReviewHandler extends AbstractSkillHandler {

    private final ReviewExplainContextService reviewContext;

    public ExplainApplicationReviewHandler(CareerContextRetriever retriever,
                                           ReviewExplainContextService reviewContext) {
        super(retriever);
        this.reviewContext = reviewContext;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.EXPLAIN_APPLICATION_REVIEW; }

    @Override
    public void assembleContext(SkillContext context) {
        context.applicationReview(reviewContext.get(context.user().userId()));
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, explaining the AI Review Pipeline — the final quality gate that
            REVIEWS an assembled application package (resume, ATS, company fit, learning, consistency,
            overall quality) before Human Review or Auto Apply. The reviewers never change the resume,
            ATS, or recommendation — they only assess them.

            TASK — Explain Review: The CONTEXT lists the user's real recent reviews, each with the
            per-reviewer scores, consistency status, quality score + category, and final verdict.

            Explain plainly what each reviewer checked and why its score is what it is, what the
            consistency status means, how the quality category was reached, and what the final verdict
            (READY / HUMAN_REVIEW / BLOCKED) means for submission (only READY is safe to auto-apply).
            Ground every statement in CONTEXT — never invent a score, category, or verdict. If there is
            no data, say the review pipeline is not enabled yet and explain what turning it on would do.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        ReviewExplainContext rc = context.applicationReview();
        if (rc == null || rc.recentReviews().isEmpty()) {
            return "CONTEXT — AI Review\nNo AI reviews yet (pipeline disabled or idle).";
        }
        StringBuilder sb = new StringBuilder("CONTEXT — AI Review\n\nRecent reviews:\n");
        for (ApplicationReview r : rc.recentReviews()) {
            sb.append("- job ").append(r.getJobId())
                    .append(" (pkg v").append(r.getPackageVersion()).append(", review v").append(r.getReviewVersion())
                    .append("): verdict ").append(r.getVerdict())
                    .append(", quality ").append(r.getQualityScore()).append(" ").append(r.getQualityCategory())
                    .append(", consistency ").append(r.getConsistencyStatus())
                    .append(" [resume ").append(nz(r.getResumeScore()))
                    .append(", ats ").append(nz(r.getAtsScore()))
                    .append(", fit ").append(nz(r.getCompanyFitScore()))
                    .append(", learning ").append(nz(r.getLearningConfidence())).append("]\n");
        }
        return sb.toString();
    }

    private static String nz(Integer v) {
        return v == null ? "—" : String.valueOf(v);
    }
}
