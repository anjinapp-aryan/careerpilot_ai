package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — grounded "should I apply?" verdict from company scores + outcome history. */
@Component
public class ShouldIApplyHandler extends AbstractCompanyIntelHandler {

    public ShouldIApplyHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.SHOULD_I_APPLY; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, advising whether the user should apply to a company. Weigh
            the observed scores (hiring probability, resume compatibility, technology match, quality)
            and the user's own history there (applications, interviews, offers, rejections). Give a
            clear YES / LEAN YES / LEAN NO / NO with the two or three strongest grounded reasons, and
            state what evidence is missing. The final decision is always the user's — never promise
            an outcome.
            """ + GROUNDING_RULES;
    }
}
