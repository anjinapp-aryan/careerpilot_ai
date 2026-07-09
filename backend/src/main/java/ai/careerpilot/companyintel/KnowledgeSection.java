package ai.careerpilot.companyintel;

/**
 * Phase 7.13 — the typed knowledge sections of the company knowledge model. Each maps to one key in
 * {@code company_knowledge.knowledge} (a JSON section map). Sections are immutable in history:
 * every change snapshots the whole map into {@code company_knowledge_version}.
 */
public enum KnowledgeSection {
    PROFILE("profile"),                     // CompanyProfile — what the company does
    CULTURE("culture"),                     // CompanyCulture / CompanyWorkStyle
    HIRING_PATTERN("hiringPattern"),        // CompanyHiringPattern / CompanyHiringTrend
    INTERVIEW_PROCESS("interviewProcess"),  // CompanyInterviewProcess — rounds, questions, focus
    TECHNOLOGY_STACK("technologyStack"),    // CompanyTechnologyStack
    SALARY_INSIGHTS("salaryInsights"),      // CompanySalaryInsights
    BENEFITS("benefits"),                   // CompanyBenefits
    LEADERSHIP("leadership"),               // CompanyLeadership
    BUSINESS_DOMAIN("businessDomain"),      // CompanyBusinessDomain
    GROWTH_SIGNALS("growthSignals"),        // CompanyGrowthSignals
    ATS_INFORMATION("atsInformation"),      // CompanyATSInformation
    REPUTATION("reputation"),               // CompanyReputation / CompanyReviewSummary
    SKILL_DEMAND("skillDemand"),            // CompanySkillDemand
    LEARNING_HISTORY("learningHistory");    // CompanyLearningHistory — outcome-derived notes

    private final String key;

    KnowledgeSection(String key) {
        this.key = key;
    }

    public String key() { return key; }

    public static KnowledgeSection fromKey(String key) {
        for (KnowledgeSection s : values()) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }
}
