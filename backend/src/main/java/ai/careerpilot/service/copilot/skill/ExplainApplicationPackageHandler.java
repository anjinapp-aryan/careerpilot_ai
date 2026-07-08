package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.ApplicationPackageValidation;
import ai.careerpilot.packageintel.api.PackageExplainContextService;
import ai.careerpilot.packageintel.api.PackageExplainContextService.PackageExplainContext;
import ai.careerpilot.packageintel.api.PackageExplainContextService.PackageSnapshot;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/**
 * Phase 7.11 — explains the Application Package: why the package is READY / HUMAN_REVIEW / BLOCKED,
 * which resume/ATS/recommendation/company-research/learning artifacts it bound, its quality (validation
 * verdict + match summary + recommendation strength), and what a blocking gate means. Grounded in the
 * real {@link ApplicationPackage}/{@link ApplicationPackageValidation} rows via
 * {@link PackageExplainContextService}; the prompt forbids inventing packages or gates the layer never produced.
 */
@Component
public class ExplainApplicationPackageHandler extends AbstractSkillHandler {

    private final PackageExplainContextService packageContext;

    public ExplainApplicationPackageHandler(CareerContextRetriever retriever,
                                            PackageExplainContextService packageContext) {
        super(retriever);
        this.packageContext = packageContext;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.EXPLAIN_APPLICATION_PACKAGE; }

    @Override
    public void assembleContext(SkillContext context) {
        context.applicationPackage(packageContext.get(context.user().userId()));
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, explaining the Application Package — the single immutable,
            validated artifact assembled before any job application is submitted.

            TASK — Explain Package: The CONTEXT lists the user's real recent packages, each with its
            package version, assembled status, validation verdict (READY / HUMAN_REVIEW / BLOCKED),
            blocking reason, recommendation strength, learning boost, and a match summary.

            Explain plainly what the package bundles (selected resume, tailored resume, ATS analysis,
            recommendation, company research, learning snapshot), why its validation verdict is what it
            is (from the gates and blocking reason shown), what BLOCKED vs HUMAN_REVIEW vs READY means
            for auto-apply (only READY is safe to auto-submit; the others stop automated submission),
            and what the quality signals (recommendation strength, match summary) indicate. Ground every
            statement in CONTEXT — never invent a package, version, gate, or score. If there is no data,
            say the package layer is not enabled yet and explain what turning it on would do.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        PackageExplainContext pc = context.applicationPackage();
        if (pc == null || pc.packages().isEmpty()) {
            return "CONTEXT — Application Package\nNo packages assembled yet (layer disabled or idle).";
        }
        StringBuilder sb = new StringBuilder("CONTEXT — Application Package\n\nRecent packages:\n");
        for (PackageSnapshot snap : pc.packages()) {
            ApplicationPackage p = snap.pkg();
            sb.append("- job ").append(p.getJobId())
                    .append(": v").append(p.getPackageVersion())
                    .append(", assembly ").append(p.getStatus())
                    .append(", validation ").append(p.getValidationStatus() == null ? "PENDING" : p.getValidationStatus());
            if (p.getRecommendationStrength() != null) sb.append(", strength ").append(p.getRecommendationStrength());
            if (p.getMatchSummary() != null) sb.append(" (").append(p.getMatchSummary()).append(")");
            ApplicationPackageValidation v = snap.validation();
            if (v != null && v.getBlockingReason() != null) sb.append(" — gate: ").append(v.getBlockingReason());
            sb.append("\n");
        }
        return sb.toString();
    }
}
