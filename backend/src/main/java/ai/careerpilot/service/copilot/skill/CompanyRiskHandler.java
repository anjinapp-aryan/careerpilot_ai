package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — surfaces observed risk signals for a company (never speculates beyond them). */
@Component
public class CompanyRiskHandler extends AbstractCompanyIntelHandler {

    public CompanyRiskHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.COMPANY_RISK; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, assessing risk signals for a company from observed data only:
            low hiring probability, high interview difficulty, repeated rejections in the user's own
            history, weak technology match, thin knowledge (little observed = uncertainty, which is
            itself a risk to state). Separate "observed risks" from "unknowns" explicitly. Never
            speculate about layoffs, finances, or internal issues not present in CONTEXT.
            """ + GROUNDING_RULES;
    }
}
