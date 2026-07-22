package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.companyintel.api.CompanyExplainContextService.CompanyDetail;
import ai.careerpilot.companyintel.api.CompanyExplainContextService.CompanyExplainContext;
import ai.careerpilot.companyintel.analyzer.CompanyInsight;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.SkillContext;

import java.util.Map;

/**
 * Phase 7.13 — shared base for the 8 Company Intelligence Copilot skills. Assembles the grounded
 * company context once and renders one CONTEXT block (known companies + scores + knowledge sections
 * + insights + similar companies) that every company skill shares; each subclass only supplies its
 * own system prompt. All prompts require grounding in CONTEXT and forbid inventing company facts.
 */
public abstract class AbstractCompanyIntelHandler extends AbstractSkillHandler {

    protected static final String GROUNDING_RULES = """

            Ground EVERY statement in the CONTEXT below — it holds only knowledge the platform actually
            observed (job postings, research, interview prep, real application outcomes). Never invent
            company facts, scores, funding, headcount, or history not in CONTEXT. If CONTEXT says the
            feature is not enabled or a company is unknown, say so plainly and explain what enabling
            Company Intelligence would add.
            """;

    private final CompanyExplainContextService companyContext;

    protected AbstractCompanyIntelHandler(CareerContextRetriever retriever,
                                          CompanyExplainContextService companyContext) {
        super(retriever);
        this.companyContext = companyContext;
    }

    @Override
    public void assembleContext(SkillContext context) {
        context.companyIntel(companyContext.get(context.user().userId(), context.message()));
    }

    @Override
    public String contextBlock(SkillContext context) {
        CompanyExplainContext cc = context.companyIntel();
        if (cc == null || !cc.enabled()) {
            return "CONTEXT — Company Intelligence\nCompany Intelligence is not enabled yet.";
        }
        StringBuilder sb = new StringBuilder("CONTEXT — Company Intelligence\n");
        if (cc.knownCompanies().isEmpty()) {
            return sb.append("No companies in the knowledge graph yet.").toString();
        }
        sb.append("\nKnown companies (name | industry | quality | hiring probability | knowledge version):\n");
        for (CompanyKnowledge c : cc.knownCompanies()) {
            sb.append("- ").append(c.getCompanyName())
                    .append(" | ").append(nullSafe(c.getIndustry()))
                    .append(" | q=").append(orNa(c.getQualityScore()))
                    .append(" | hire=").append(orNa(c.getHiringProbability()))
                    .append(" | v").append(c.getKnowledgeVersion()).append('\n');
        }
        for (CompanyDetail d : cc.mentioned()) {
            CompanyKnowledge c = d.company();
            sb.append("\nDETAIL — ").append(c.getCompanyName()).append('\n');
            sb.append("Scores: quality=").append(orNa(c.getQualityScore()))
                    .append(", interviewDifficulty=").append(orNa(c.getInterviewDifficulty()))
                    .append(", hiringProbability=").append(orNa(c.getHiringProbability()))
                    .append(", resumeCompatibility=").append(orNa(c.getResumeCompatibility()))
                    .append(", technologyMatch=").append(orNa(c.getTechnologyMatch()))
                    .append(", careerGrowth=").append(orNa(c.getCareerGrowth()))
                    .append(", salaryPotential=").append(orNa(c.getSalaryPotential()))
                    .append(", cultureMatch=").append(orNa(c.getCultureMatch()))
                    .append(", learningValue=").append(orNa(c.getLearningValue()))
                    .append(", longTermOpportunity=").append(orNa(c.getLongTermOpportunity())).append('\n');
            for (Map.Entry<String, String> section : d.sections().entrySet()) {
                sb.append(section.getKey()).append(": ").append(truncate(section.getValue(), 800)).append('\n');
            }
            for (CompanyInsight insight : d.insights()) {
                sb.append("[").append(insight.kind()).append("] ").append(insight.headline())
                        .append(" — ").append(insight.detail()).append('\n');
            }
            if (!d.similar().isEmpty()) {
                sb.append("Similar companies: ");
                d.similar().forEach(sim -> sb.append(sim.companyName())
                        .append(" (").append(sim.similarity()).append("%) "));
                sb.append('\n');
            }
            // Phase 7.17.2 — real interview data only; absent entirely when no rounds are on file
            // (never fabricate an interview history that wasn't observed).
            Map<String, Object> interview = d.interviewIntelligence();
            Object roundCount = interview == null ? null : interview.get("interviewRounds");
            if (roundCount instanceof Integer rc && rc > 0) {
                Object passRate = interview.get("passRate");
                Object avgRating = interview.get("avgFeedbackRating");
                sb.append("Interview data (").append(rc).append(" real rounds observed): ")
                        .append("passRate=").append(passRate == null ? "N/A" : passRate)
                        .append(" (self-assessed, not employer-confirmed), ")
                        .append("avgFeedbackRating=").append(avgRating == null ? "N/A" : avgRating)
                        .append(", confidence=").append(interview.get("confidence")).append('\n');
            }
        }
        if (cc.mentioned().isEmpty()) {
            sb.append("\nNo specific company was recognized in the question — answer from the list above ")
                    .append("and invite the user to name a company for detail.\n");
        }
        return sb.toString();
    }
}
