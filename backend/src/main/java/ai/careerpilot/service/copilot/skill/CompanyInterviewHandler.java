package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — analyzes a company's persisted interview knowledge (process, difficulty, history). */
@Component
public class CompanyInterviewHandler extends AbstractCompanyIntelHandler {

    public CompanyInterviewHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.COMPANY_INTERVIEW; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, analyzing a company's interview picture: the persisted
            interviewProcess knowledge (rounds, question styles, technical focus, STAR expectations
            from earlier prep runs), the interview-difficulty score with its explanation, and the
            user's own interview history there. Advise what to prepare, reusing what was already
            observed rather than generic advice.
            """ + GROUNDING_RULES;
    }
}
