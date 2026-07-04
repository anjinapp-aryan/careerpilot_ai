package ai.careerpilot.workflow.tracking;

import java.util.Map;
import java.util.Set;

import static ai.careerpilot.domain.ApplicationLifecycle.*;

/**
 * Phase 3A.1 — the pure, deterministic transition table for an application's lifecycle. No I/O, no
 * dependencies: unit-tested exhaustively. Any status may transition to a terminal
 * {@code WITHDRAWN}/{@code EXPIRED}; {@code REJECTED} is reachable from any active (non-terminal)
 * status. Terminal states ({@code ACCEPTED}, {@code REJECTED}, {@code WITHDRAWN}, {@code EXPIRED})
 * have no outgoing transitions.
 */
public final class ApplicationStatusMachine {

    private ApplicationStatusMachine() {}

    private static final Set<String> TERMINAL = Set.of(STATUS_ACCEPTED, STATUS_REJECTED, STATUS_WITHDRAWN, STATUS_EXPIRED);

    /** Statuses any active row can always move to (rejection / withdrawal / expiry). */
    private static final Set<String> ALWAYS = Set.of(STATUS_REJECTED, STATUS_WITHDRAWN, STATUS_EXPIRED);

    private static final Map<String, Set<String>> FORWARD = Map.ofEntries(
            Map.entry(STATUS_DRAFT, Set.of(STATUS_SUBMITTED)),
            Map.entry(STATUS_SUBMITTED, Set.of(STATUS_VIEWED, STATUS_UNDER_REVIEW)),
            Map.entry(STATUS_VIEWED, Set.of(STATUS_UNDER_REVIEW, STATUS_ASSESSMENT)),
            Map.entry(STATUS_UNDER_REVIEW, Set.of(STATUS_ASSESSMENT, STATUS_TECHNICAL_INTERVIEW, STATUS_HR_INTERVIEW)),
            Map.entry(STATUS_ASSESSMENT, Set.of(STATUS_TECHNICAL_INTERVIEW)),
            Map.entry(STATUS_TECHNICAL_INTERVIEW, Set.of(STATUS_SYSTEM_DESIGN, STATUS_MANAGER_INTERVIEW, STATUS_HR_INTERVIEW, STATUS_FINAL_ROUND)),
            Map.entry(STATUS_SYSTEM_DESIGN, Set.of(STATUS_MANAGER_INTERVIEW, STATUS_HR_INTERVIEW, STATUS_FINAL_ROUND)),
            Map.entry(STATUS_MANAGER_INTERVIEW, Set.of(STATUS_HR_INTERVIEW, STATUS_FINAL_ROUND)),
            Map.entry(STATUS_HR_INTERVIEW, Set.of(STATUS_FINAL_ROUND, STATUS_OFFER_RECEIVED)),
            Map.entry(STATUS_FINAL_ROUND, Set.of(STATUS_OFFER_RECEIVED)),
            Map.entry(STATUS_OFFER_RECEIVED, Set.of(STATUS_NEGOTIATION, STATUS_ACCEPTED)),
            Map.entry(STATUS_NEGOTIATION, Set.of(STATUS_ACCEPTED)));

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
        if (isTerminal(from)) return false;                       // terminal is a dead end
        if (ALWAYS.contains(to)) return true;                     // reject/withdraw/expire from any active state
        return FORWARD.getOrDefault(from, Set.of()).contains(to);
    }
}
