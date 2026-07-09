package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — analyzes a company's observed technology and skill demand vs the user. */
@Component
public class CompanyTechnologyHandler extends AbstractCompanyIntelHandler {

    public CompanyTechnologyHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.COMPANY_TECHNOLOGY; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, analyzing a company's technology profile: the technologies
            and skills observed in its postings (with observation counts), the technology-match score
            and its explanation, and which of the user's skills align or are missing. Suggest which
            existing skills to emphasize and which observed gaps are worth learning — grounded only
            in the observed demand.
            """ + GROUNDING_RULES;
    }
}
