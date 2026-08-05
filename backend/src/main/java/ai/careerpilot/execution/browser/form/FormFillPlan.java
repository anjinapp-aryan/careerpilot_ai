package ai.careerpilot.execution.browser.form;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 12C — the complete, inspectable decision about what will be typed into a form, produced
 * <b>before</b> a single keystroke is sent.
 *
 * <p>Separating the plan from its execution is what makes this engine auditable. The plan can be
 * logged, asserted on in tests, attached to evidence, and shown to a human on the approval screen —
 * all without a browser. It is also what lets {@link #hasBlockingGaps()} refuse to submit a form
 * whose required fields cannot be honestly filled, instead of discovering that halfway through.
 */
public record FormFillPlan(List<PlannedFill> fills, List<UnresolvedField> unresolved) {

    public FormFillPlan {
        fills = fills == null ? List.of() : List.copyOf(fills);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    public static FormFillPlan empty() {
        return new FormFillPlan(List.of(), List.of());
    }

    /** One field that will be filled, with the provenance of its value. */
    public record PlannedFill(DiscoveredField field, CanonicalField canonical, String value, String source) {}

    /**
     * One field that will be left alone, and why. {@code required} is tracked separately because a
     * required unresolved field is a blocker while an optional one is merely a gap.
     */
    public record UnresolvedField(DiscoveredField field, CanonicalField canonical,
                                  String reason, boolean required) {}

    /**
     * Required fields with no verified value. <b>A non-empty result must stop the submission.</b>
     * Submitting anyway either fails the employer's own validation or — worse — delivers an
     * incomplete application under the candidate's real name.
     */
    public List<UnresolvedField> blockingGaps() {
        return unresolved.stream().filter(UnresolvedField::required).toList();
    }

    public boolean hasBlockingGaps() {
        return !blockingGaps().isEmpty();
    }

    public boolean isEmpty() {
        return fills.isEmpty() && unresolved.isEmpty();
    }

    /** Whether a given canonical field is present in the plan at all (filled or not). */
    public boolean covers(CanonicalField canonical) {
        return fills.stream().anyMatch(f -> f.canonical() == canonical)
                || unresolved.stream().anyMatch(u -> u.canonical() == canonical);
    }

    /**
     * A no-PII summary for logs, metrics and evidence. Emits field identities and counts —
     * <b>never values</b>, since those are the candidate's personal data and this summary may reach
     * a log aggregator.
     */
    public Map<String, Object> summary() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("plannedFills", fills.size());
        out.put("unresolved", unresolved.size());
        out.put("blockingGaps", blockingGaps().size());
        out.put("filledFields", fills.stream().map(f -> f.canonical().name()).toList());
        out.put("unresolvedFields", unresolved.stream()
                .collect(Collectors.toMap(
                        u -> u.field().displayName(),
                        UnresolvedField::reason,
                        (a, b) -> a,
                        java.util.LinkedHashMap::new)));
        return out;
    }
}
