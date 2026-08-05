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
    PERSONALIZED_RECOMMENDATIONS("recommendations", "Recommendations"),
    EXPLAIN_LEARNING("explain_learning", "Explain learning"),
    EXPLAIN_APPLICATION_DECISION("explain_application_decision", "Explain application decision"),
    EXPLAIN_APPLICATION_PACKAGE("explain_application_package", "Explain application package"),
    EXPLAIN_APPLICATION_REVIEW("explain_application_review", "Explain application review"),
    EXPLAIN_COMPANY("explain_company", "Explain company"),
    COMPARE_COMPANIES("compare_companies", "Compare companies"),
    SHOULD_I_APPLY("should_i_apply", "Should I apply"),
    COMPANY_RISK("company_risk", "Company risk"),
    COMPANY_TECHNOLOGY("company_technology", "Company technology"),
    COMPANY_INTERVIEW("company_interview", "Company interview analysis"),
    COMPANY_CULTURE("company_culture", "Company culture"),
    COMPANY_GROWTH("company_growth", "Company career growth"),
    JOB_DISCOVERY_HEALTH("job_discovery_health", "Job discovery provider health"),
    // Phase 7.15 — STAR Story Intelligence & Behavioral AI
    SUGGEST_STAR_STORY("suggest_star_story", "Suggest best STAR story"),
    GENERATE_STAR_STORY("generate_star_story", "Generate/improve STAR story"),
    // Phase 7.16 — Real Application Submission Pipeline
    SUBMISSION_STATUS("submission_status", "Application submission status"),
    EXPLAIN_SUBMISSION_STRATEGY("explain_submission_strategy", "Explain submission strategy"),
    // Applications Page — AI Job Application Command Center
    EXPLAIN_APPLICATION_STATUS("explain_application_status", "Explain application status"),
    // Gap B — Offer Intelligence & Salary Negotiation
    EXPLAIN_OFFER("explain_offer", "Explain offer"),
    COMPARE_OFFERS("compare_offers", "Compare offers"),
    // Phase 11B — Intelligent Actions & Cross-System Queries
    DAILY_PRIORITY_BRIEFING("daily_priority_briefing", "What should I do today");

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
