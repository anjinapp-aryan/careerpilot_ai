package ai.careerpilot.autopilot.provider;

/**
 * Phase 7.4 — result status of an application-submission attempt.
 *
 * <ul>
 *   <li>{@code SUBMITTED} — the application was actually delivered to the external ATS. Only a
 *       provider with a real, credentialed integration may return this; the agent never fabricates it.</li>
 *   <li>{@code HUMAN_REVIEW} — the provider cannot (or is not configured to) submit automatically;
 *       the application is handed back to a human. This is the safe default.</li>
 *   <li>{@code FAILED} — a genuine error occurred during a real submit attempt.</li>
 * </ul>
 */
public enum SubmissionStatus {
    SUBMITTED,
    HUMAN_REVIEW,
    FAILED
}
