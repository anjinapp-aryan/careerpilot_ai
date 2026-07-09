package ai.careerpilot.companyintel;

/**
 * Phase 7.13 — where a knowledge revision came from. Knowledge is only ever written from these real
 * platform artifacts; there is no "generated" source, because fabrication is forbidden.
 */
public enum KnowledgeSource {
    DAILY_DISCOVERY,
    JOB_DISCOVERY,
    COMPANY_RESEARCH,
    INTERVIEW_PREP,
    APPLICATION_OUTCOME,
    LEARNING_ENGINE,
    RECOMMENDATION_ENGINE,
    RESUME_TAILORING,
    WORKFLOW,
    USER_APPLICATION,
    HISTORICAL,
    ROLLBACK
}
