package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class PersonalizedRecommendationsHandler extends AbstractSkillHandler {

    public PersonalizedRecommendationsHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.PERSONALIZED_RECOMMENDATIONS; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var profile = retriever.getUserProfileContext(context.user());
            context.userProfile(profile);
            var resume = retriever.getResumeContext(context.user(), null);
            context.resume(resume);
            log.debug("Personalized recommendations context assembled");
        } catch (Exception e) {
            log.warn("Could not load context for recommendations: {}", e.getMessage());
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, a personalized career coach.

            TASK — Personalized Recommendations: Based on the candidate's profile, resume,
            and application history in CONTEXT, provide tailored career recommendations.
            Include:
            - Top 3 immediate actions to improve career prospects
            - Role types and companies that fit their profile
            - Areas to focus development efforts
            - Networking and visibility strategies
            - Timeline and milestones for career growth

            Be specific and personalized to their unique situation.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var profile = context.userProfile();
        var resume = context.resume();

        StringBuilder sb = new StringBuilder("CONTEXT — Personalized Recommendations\n\n");

        if (profile != null) {
            sb.append("CANDIDATE ACTIVITY\n");
            sb.append("Total Resumes: ").append(profile.resumeCount()).append("\n");
            sb.append("Active Applications: ").append(profile.applicationCount()).append("\n");
            sb.append("Completed Workflow Analyses: ").append(profile.workflowCount()).append("\n\n");
        }

        if (resume != null) {
            sb.append("PRIMARY RESUME\n");
            sb.append("Filename: ").append(nullSafe(resume.filename())).append("\n");
            sb.append("Resume Score: ").append(orNa(resume.resumeScore())).append("/100\n");
            sb.append("ATS Score: ").append(orNa(resume.atsScore())).append("/100\n");
            sb.append("Skills: ").append(truncate(nullSafe(resume.skillsJson()), MAX_SKILL_CHARS)).append("\n");
        }

        return sb.toString();
    }
}
