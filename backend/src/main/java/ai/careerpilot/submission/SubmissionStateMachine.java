package ai.careerpilot.submission;

import java.util.Map;
import java.util.Set;

import static ai.careerpilot.domain.ApplicationSubmissionSession.*;

/**
 * Phase 7.16 — the pure, deterministic transition table for {@link
 * ai.careerpilot.domain.ApplicationSubmissionSession#getStatus()}. No I/O, no dependencies:
 * unit-tested exhaustively, mirroring {@code ApplicationStatusMachine}'s style. Any active
 * (non-terminal) status may transition to {@code FAILED} — fail-closed, any stage can abort.
 * {@code COMPLETED} and {@code FAILED} are terminal with no outgoing transitions.
 */
public final class SubmissionStateMachine {

    private SubmissionStateMachine() {}

    private static final Set<String> TERMINAL = Set.of(STATUS_COMPLETED, STATUS_FAILED, STATUS_USER_REPORTED_SUBMITTED);

    private static final Map<String, Set<String>> FORWARD = Map.ofEntries(
            Map.entry(STATUS_CREATED, Set.of(STATUS_VALIDATING)),
            Map.entry(STATUS_VALIDATING, Set.of(STATUS_PACKAGE_READY)),
            Map.entry(STATUS_PACKAGE_READY, Set.of(STATUS_REVIEW_READY)),
            Map.entry(STATUS_REVIEW_READY, Set.of(STATUS_COMPANY_READY)),
            Map.entry(STATUS_COMPANY_READY, Set.of(STATUS_STAR_READY)),
            Map.entry(STATUS_STAR_READY, Set.of(STATUS_READY_FOR_SUBMISSION)),
            Map.entry(STATUS_READY_FOR_SUBMISSION, Set.of(STATUS_WAITING_APPROVAL, STATUS_SUBMITTING)),
            Map.entry(STATUS_WAITING_APPROVAL, Set.of(STATUS_SUBMITTING)),
            // Phase 7.16.5 — SUBMITTING no longer advances to SUBMITTED unconditionally. It only
            // does so when the underlying ApplicationExecution genuinely reached SUBMITTED (a real
            // ATS-connector or Playwright guest-apply click actually happened); every other
            // execution outcome (no backend configured, ineligible connector, or the Gap D
            // form-screenshot approval still pending) routes to WAITING_MANUAL_SUBMISSION instead —
            // a deliberate dead end for now (like WAITING_APPROVAL, resting until a future
            // manual-confirm action), never silently relabeled SUBMITTED.
            // Phase 0 (Browser Automation Platform) — SUBMIT_UNVERIFIED is the third outcome of a
            // submit attempt: the click happened but could not be verified. It proceeds to
            // TRACKING (the application probably does exist, so it must be tracked) without ever
            // being labelled SUBMITTED, and without being routed to WAITING_MANUAL_SUBMISSION,
            // which would invite a duplicate application.
            Map.entry(STATUS_SUBMITTING, Set.of(STATUS_SUBMITTED, STATUS_SUBMIT_UNVERIFIED,
                    STATUS_WAITING_MANUAL_SUBMISSION)),
            Map.entry(STATUS_SUBMIT_UNVERIFIED, Set.of(STATUS_TRACKING)),
            // Guided Apply — the one legal way out of the dead end: an explicit user confirmation
            // that they completed the employer's application themselves. Never reached any other
            // way (no automatic inference, no timer, no retry).
            Map.entry(STATUS_WAITING_MANUAL_SUBMISSION, Set.of(STATUS_USER_REPORTED_SUBMITTED)),
            // Phase 7.16.1 — VERIFYING inserted so VERIFIED is never a bare transition; it can
            // only be reached after a real evidence check. VERIFICATION_FAILED is deliberately
            // NOT terminal — it still proceeds to TRACKING (the application likely was
            // submitted; we simply couldn't prove it), it's a different label, not an abort.
            Map.entry(STATUS_SUBMITTED, Set.of(STATUS_VERIFYING)),
            Map.entry(STATUS_VERIFYING, Set.of(STATUS_VERIFIED, STATUS_VERIFICATION_FAILED)),
            Map.entry(STATUS_VERIFIED, Set.of(STATUS_TRACKING)),
            Map.entry(STATUS_VERIFICATION_FAILED, Set.of(STATUS_TRACKING)),
            Map.entry(STATUS_TRACKING, Set.of(STATUS_COMPLETED)));

    /**
     * Guided Apply Hardening — every status that means "this session no longer needs the candidate
     * to manually complete the employer's form", derived directly from the transition graph above
     * rather than a separately-maintained list: {@code SUBMITTED}/{@code SUBMIT_UNVERIFIED}/{@code
     * VERIFYING}/{@code VERIFIED}/{@code VERIFICATION_FAILED}/{@code TRACKING}/{@code COMPLETED} are
     * reachable ONLY via {@code SUBMITTING} (a genuine automated submit attempt actually happened),
     * never via {@code WAITING_MANUAL_SUBMISSION} — the FORWARD map above has no edge from the
     * latter into any of them. {@code USER_REPORTED_SUBMITTED} is the one explicit user-confirmed
     * exit from {@code WAITING_MANUAL_SUBMISSION} itself. Deliberately excludes {@code FAILED}: a
     * failed session (e.g. package assembly failed before submission was ever attempted) does not
     * mean the underlying application need went away.
     *
     * <p>Consumed by {@code ApplicationCardService} to suppress a stale "Guided Apply Required"
     * banner once the candidate's submission history for that job has moved past the point where
     * manual completion is still the open action.
     */
    private static final Set<String> RESOLVES_MANUAL_COMPLETION = Set.of(
            STATUS_SUBMITTED, STATUS_SUBMIT_UNVERIFIED, STATUS_VERIFYING, STATUS_VERIFIED,
            STATUS_VERIFICATION_FAILED, STATUS_TRACKING, STATUS_COMPLETED, STATUS_USER_REPORTED_SUBMITTED);

    public static boolean resolvesManualCompletion(String status) {
        return RESOLVES_MANUAL_COMPLETION.contains(status);
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }

    public static boolean isKnown(String status) {
        return FORWARD.containsKey(status) || TERMINAL.contains(status);
    }

    /** True when {@code to} is a legal next status from {@code from}. */
    public static boolean canTransition(String from, String to) {
        if (from == null || to == null || from.equals(to)) return false;
        if (!isKnown(from) || !isKnown(to)) return false;
        if (isTerminal(from)) return false;
        if (STATUS_FAILED.equals(to)) return true; // fail-closed from any active state
        return FORWARD.getOrDefault(from, Set.of()).contains(to);
    }
}
