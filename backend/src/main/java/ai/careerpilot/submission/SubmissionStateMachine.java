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

    private static final Set<String> TERMINAL = Set.of(STATUS_COMPLETED, STATUS_FAILED);

    private static final Map<String, Set<String>> FORWARD = Map.ofEntries(
            Map.entry(STATUS_CREATED, Set.of(STATUS_VALIDATING)),
            Map.entry(STATUS_VALIDATING, Set.of(STATUS_PACKAGE_READY)),
            Map.entry(STATUS_PACKAGE_READY, Set.of(STATUS_REVIEW_READY)),
            Map.entry(STATUS_REVIEW_READY, Set.of(STATUS_COMPANY_READY)),
            Map.entry(STATUS_COMPANY_READY, Set.of(STATUS_STAR_READY)),
            Map.entry(STATUS_STAR_READY, Set.of(STATUS_READY_FOR_SUBMISSION)),
            Map.entry(STATUS_READY_FOR_SUBMISSION, Set.of(STATUS_WAITING_APPROVAL, STATUS_SUBMITTING)),
            Map.entry(STATUS_WAITING_APPROVAL, Set.of(STATUS_SUBMITTING)),
            Map.entry(STATUS_SUBMITTING, Set.of(STATUS_SUBMITTED)),
            Map.entry(STATUS_SUBMITTED, Set.of(STATUS_VERIFIED)),
            Map.entry(STATUS_VERIFIED, Set.of(STATUS_TRACKING)),
            Map.entry(STATUS_TRACKING, Set.of(STATUS_COMPLETED)));

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
