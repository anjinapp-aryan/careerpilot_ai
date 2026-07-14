package ai.careerpilot.submission.question;

/**
 * Phase 7.16 — the fixed taxonomy of common application-question categories the
 * {@link QuestionDetectionService} classifies supplied question text into, plus the always-run
 * fixed set of 11 common questions the orchestrator asks {@link
 * ai.careerpilot.submission.answer.AnswerGenerationService} to answer for every session so the
 * approval screen always has something to show.
 */
public enum QuestionCategory {
    WHY_ROLE,
    ABOUT_YOU,
    LEADERSHIP,
    CONFLICT,
    FAILURE,
    ACHIEVEMENT,
    SALARY,
    NOTICE_PERIOD,
    VISA,
    RELOCATION,
    REMOTE_PREFERENCE,
    OTHER
}
