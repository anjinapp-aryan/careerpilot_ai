package ai.careerpilot.domain;

import java.util.Locale;

/**
 * Guided Apply — why automation could not safely continue and CareerPilot handed the application
 * to the candidate instead of submitting it.
 *
 * <p>Deliberately <b>not</b> a persisted column. Every value here is derived, read-time, from the
 * free-text {@code ApplicationExecution.failureReason} that the existing execution path (Actions
 * 1-6, unmodified) already writes at each of its distinct abort sites — the same "derive, don't
 * trust a lagging column" discipline as {@code WorkflowService#deriveDisplayStatus} and {@code
 * ApplicationCardService#deriveAutomationHealth}. No change to {@code GuestApplyAutomationService},
 * {@code AttemptOutcome}, or {@code ApplicationExecutionService} was needed to support this: those
 * classes already produce a distinguishable reason string per case, and CAPTCHA/blocker detection
 * itself ({@code CaptchaLoginDetector}) is untouched.
 */
public enum GuidedApplyReason {
    CAPTCHA,
    BOT_PROTECTION,
    LOGIN_REQUIRED,
    UNSUPPORTED_CONTROL,
    EMPLOYER_RESTRICTION,
    AUTOMATION_BLOCKED,
    MANUAL_REQUIRED,
    UNKNOWN_BLOCKER;

    /**
     * Classifies {@code ApplicationExecution.failureReason} into a stable reason. Keyword matching,
     * the same deterministic discipline as {@code FieldClassifier}/{@code CopilotSkillRouter} — no
     * LLM, and never a guess: an unmatched-but-present reason is honestly {@link #MANUAL_REQUIRED}
     * ("automation genuinely didn't run, no more specific cause identified"), and only a null/blank
     * reason is {@link #UNKNOWN_BLOCKER}.
     */
    public static GuidedApplyReason fromFailureReason(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) return UNKNOWN_BLOCKER;
        String r = failureReason.toLowerCase(Locale.ROOT);
        if (r.contains("captcha") || r.contains("login wall")) return CAPTCHA;
        if (r.contains("not guest-apply eligible")) return AUTOMATION_BLOCKED;
        if (r.contains("required field") && r.contains("could not be filled")) return MANUAL_REQUIRED;
        if (r.contains("approval enqueue failed")) return UNKNOWN_BLOCKER;
        return MANUAL_REQUIRED;
    }
}
