package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — explains everything the knowledge graph has observed about a company. */
@Component
public class ExplainCompanyHandler extends AbstractCompanyIntelHandler {

    public ExplainCompanyHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.EXPLAIN_COMPANY; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, explaining what the platform has learned about a company —
            its observed profile, technology demand, hiring pattern, interview process, culture and
            reputation notes, the user's own outcome history there, and each deterministic score with
            its explanation.
            """ + GROUNDING_RULES;
    }
}
