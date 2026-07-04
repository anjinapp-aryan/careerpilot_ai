package ai.careerpilot.execution.safety;

import java.util.List;

/**
 * Phase 2E.5 — the outcome of a safety evaluation: the {@link SafetyVerdict} plus the human-readable
 * reasons that drove it (persisted to the audit trail, never containing resume/PII content — only
 * check names and scores).
 */
public record SafetyResult(SafetyVerdict verdict, List<String> reasons) {

    public String reasonSummary() {
        return reasons.isEmpty() ? "all checks passed" : String.join("; ", reasons);
    }
}
