package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — explains a company's observed culture/reputation knowledge. */
@Component
public class CompanyCultureHandler extends AbstractCompanyIntelHandler {

    public CompanyCultureHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.COMPANY_CULTURE; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, discussing a company's culture using only the culture,
            reputation, benefits and leadership knowledge sections on file plus the culture-match
            score and its explanation. Culture knowledge is often thin — be explicit about how much
            is actually observed versus unknown, and never fill gaps with stereotypes about the
            company or its industry.
            """ + GROUNDING_RULES;
    }
}
