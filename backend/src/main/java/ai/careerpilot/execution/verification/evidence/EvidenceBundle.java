package ai.careerpilot.execution.verification.evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 0 — every {@link VerificationSignal} gathered about one submission attempt, in the order
 * observed. Purely a carrier: it holds no verdict, because deciding what the signals mean is
 * {@link VerificationAdjudicator}'s single responsibility.
 *
 * <p>An empty bundle is a legitimate, common outcome and must be treated as "no evidence", never
 * as "assume success" — that inversion is exactly the defect this phase removes.
 */
public record EvidenceBundle(List<VerificationSignal> signals) {

    public EvidenceBundle {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public static EvidenceBundle empty() {
        return new EvidenceBundle(List.of());
    }

    public static EvidenceBundle of(VerificationSignal... signals) {
        return new EvidenceBundle(List.of(signals));
    }

    public boolean isEmpty() {
        return signals.isEmpty();
    }

    public boolean has(SignalType type) {
        return signals.stream().anyMatch(s -> s.type() == type);
    }

    public long countOf(SignalStrength strength) {
        return signals.stream().filter(s -> s.strength() == strength).count();
    }

    /** A new bundle with one more signal appended. Never mutates — bundles are shared across the audit trail. */
    public EvidenceBundle with(VerificationSignal signal) {
        if (signal == null) return this;
        List<VerificationSignal> next = new ArrayList<>(signals);
        next.add(signal);
        return new EvidenceBundle(next);
    }

    /** Compact, log-safe summary. Contains observation metadata only — never applicant field values. */
    public String describe() {
        if (signals.isEmpty()) return "no signals";
        return signals.stream()
                .map(s -> s.type() + "(" + s.strength() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("no signals");
    }
}
