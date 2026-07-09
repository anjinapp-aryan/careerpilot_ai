package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — career-growth analysis from observed growth signals and role breadth. */
@Component
public class CompanyGrowthHandler extends AbstractCompanyIntelHandler {

    public CompanyGrowthHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.COMPANY_GROWTH; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, assessing what a company could mean for the user's career
            growth: the career-growth, learning-value and long-term-opportunity scores with their
            explanations, observed role breadth and hiring trend, and how the company's observed
            technology demand overlaps the user's direction. Frame growth as what the evidence
            supports, not as a promise.
            """ + GROUNDING_RULES;
    }
}
