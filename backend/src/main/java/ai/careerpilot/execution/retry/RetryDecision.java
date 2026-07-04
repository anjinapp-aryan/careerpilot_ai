package ai.careerpilot.execution.retry;

import ai.careerpilot.domain.ApplicationRetry;

/**
 * Phase 2E.6 — the pure output of {@link RetryPolicyService#decide}: what to do about a failed
 * attempt. {@code shouldRetry()} is true only for {@code RETRY}/{@code RETRY_BACKOFF}; {@code PAUSE}
 * and {@code STOP} are both terminal for the automated path (PAUSE parks for human attention, STOP
 * abandons), so neither loops.
 */
public record RetryDecision(String failureClass, String action, long backoffMs) {

    public boolean shouldRetry() {
        return ApplicationRetry.ACTION_RETRY.equals(action)
                || ApplicationRetry.ACTION_RETRY_BACKOFF.equals(action);
    }

    public boolean isTerminal() {
        return ApplicationRetry.ACTION_STOP.equals(action)
                || ApplicationRetry.ACTION_PAUSE.equals(action);
    }
}
