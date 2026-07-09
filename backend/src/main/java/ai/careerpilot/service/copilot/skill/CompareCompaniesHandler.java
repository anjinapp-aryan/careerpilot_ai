package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.companyintel.api.CompanyExplainContextService;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/** Phase 7.13 — side-by-side comparison of two (or more) companies from observed knowledge. */
@Component
public class CompareCompaniesHandler extends AbstractCompanyIntelHandler {

    public CompareCompaniesHandler(CareerContextRetriever retriever, CompanyExplainContextService companyContext) {
        super(retriever, companyContext);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.COMPARE_COMPANIES; }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, comparing companies the user asks about, dimension by
            dimension: quality, hiring probability, interview difficulty, technology/skill demand,
            culture signals, salary insight, growth, and the user's own outcome history at each.
            Present a clear side-by-side comparison and a grounded recommendation of which is the
            stronger target and why. If a named company is not in CONTEXT, say it is not yet known.
            """ + GROUNDING_RULES;
    }
}
