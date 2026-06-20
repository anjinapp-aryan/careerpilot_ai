package ai.careerpilot.service.copilot;

/**
 * The 10 supported Copilot skills, each backed by a specialized handler.
 * These are invoked when the user's message matches a skill intent.
 */
public enum CopilotSkill {
    RESUME_ANALYSIS("resume_analysis", "Analyze my resume"),
    ATS_ANALYSIS("ats_analysis", "ATS analysis"),
    JOB_MATCH_ANALYSIS("job_match", "Job match"),
    APPLICATION_STRATEGY("application_strategy", "Application strategy"),
    INTERVIEW_PREPARATION("interview_prep", "Interview preparation"),
    CAREER_GUIDANCE("career_guidance", "Career guidance"),
    WORKFLOW_EXPLANATION("workflow_explanation", "Workflow explanation"),
    SALARY_GUIDANCE("salary_guidance", "Salary guidance"),
    SKILLS_GAP_ANALYSIS("skills_gap", "Skills gap"),
    PERSONALIZED_RECOMMENDATIONS("recommendations", "Recommendations");

    private final String key;
    private final String displayName;

    CopilotSkill(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String key() { return key; }
    public String displayName() { return displayName; }

    public static CopilotSkill fromAction(String action) {
        if (action == null) return null;
        for (CopilotSkill skill : values()) {
            if (skill.key.equals(action)) return skill;
        }
        return null;
    }
}
