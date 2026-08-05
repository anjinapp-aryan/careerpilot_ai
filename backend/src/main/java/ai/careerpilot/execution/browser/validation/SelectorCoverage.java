package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.execution.browser.form.DiscoveredField;
import ai.careerpilot.execution.browser.form.FieldControlType;
import ai.careerpilot.execution.browser.form.FormFillPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 12C.5 — per-page selector metrics, computed from a completed discovery + plan. Pure
 * arithmetic over data both already produced; it discovers nothing itself and calls nothing.
 *
 * <p>The distinction the counters draw is the one an operator actually needs:
 * <ul>
 *   <li><b>unsupported</b> — the engine cannot <em>drive</em> this control (custom widget, an
 *       element type outside the HTML form vocabulary). Fixing it is engine work.</li>
 *   <li><b>unknown</b> — the engine can drive it but cannot <em>identify</em> what it means.
 *       Fixing it is classifier work, and it is usually one rule.</li>
 *   <li><b>missingRequiredValues</b> — identified, drivable, and we have no verified data.
 *       Fixing it is a data/product gap, not automation work at all.</li>
 * </ul>
 * Collapsing those three into one "failed" number is how a fixable classifier rule gets mistaken
 * for an unsupportable ATS.
 */
public record SelectorCoverage(
        int totalControls,
        int fillableControls,
        int supportedControls,
        int unsupportedControls,
        int unknownControls,
        int requiredControls,
        int mappedControls,
        int missingRequiredValues,
        Map<String, Integer> categoryDistribution) {

    public SelectorCoverage {
        categoryDistribution = categoryDistribution == null
                ? Map.of() : Map.copyOf(categoryDistribution);
    }

    public static SelectorCoverage empty() {
        return new SelectorCoverage(0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }

    /**
     * @param discovered every control the discovery script reported
     * @param classified the canonical identity of each, positionally aligned with {@code discovered}
     * @param plan       the fill decision produced from them
     */
    public static SelectorCoverage from(List<DiscoveredField> discovered,
                                        List<CanonicalField> classified,
                                        FormFillPlan plan) {
        if (discovered == null || discovered.isEmpty()) return empty();

        int total = discovered.size();
        int fillable = 0;
        int unsupported = 0;
        int unknown = 0;
        int required = 0;
        Map<String, Integer> distribution = new TreeMap<>();

        for (int i = 0; i < discovered.size(); i++) {
            DiscoveredField field = discovered.get(i);
            CanonicalField canonical = classified != null && i < classified.size()
                    ? classified.get(i) : CanonicalField.UNKNOWN;

            distribution.merge(canonical.name(), 1, Integer::sum);
            if (field.required()) required++;
            if (field.controlType() == FieldControlType.UNSUPPORTED) {
                unsupported++;
                continue;
            }
            if (!field.isFillable()) continue;   // hidden/disabled/read-only: not a coverage failure
            fillable++;
            if (canonical == CanonicalField.UNKNOWN) unknown++;
        }

        int mapped = plan == null ? 0 : plan.fills().size();
        int missingRequired = plan == null ? 0 : plan.blockingGaps().size();
        int supported = Math.max(0, fillable - unknown);

        return new SelectorCoverage(total, fillable, supported, unsupported, unknown,
                required, mapped, missingRequired, distribution);
    }

    /** Fraction of drivable controls the classifier could identify, in {@code [0,1]}. */
    public double classificationCoverage() {
        return fillableControls == 0 ? 1.0 : (double) supportedControls / fillableControls;
    }

    /**
     * Fraction of required controls that will actually receive a verified value.
     *
     * <p>Returns {@code 1.0} when a page declares no required fields — which is genuinely common
     * (many ATSes enforce requiredness in JavaScript rather than with the {@code required}
     * attribute) and must not be scored as a failure. The honest consequence is stated in
     * {@link AutomationConfidence}: a page with zero declared required fields cannot be proven
     * ready by this metric alone.
     */
    public double requiredCoverage() {
        if (requiredControls == 0) return 1.0;
        int satisfied = Math.max(0, requiredControls - missingRequiredValues);
        return (double) satisfied / requiredControls;
    }

    /** Fraction of drivable controls the engine cannot operate at all. */
    public double unsupportedRatio() {
        int denominator = fillableControls + unsupportedControls;
        return denominator == 0 ? 0.0 : (double) unsupportedControls / denominator;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalControls", totalControls);
        out.put("fillableControls", fillableControls);
        out.put("supportedControls", supportedControls);
        out.put("unsupportedControls", unsupportedControls);
        out.put("unknownControls", unknownControls);
        out.put("requiredControls", requiredControls);
        out.put("mappedControls", mappedControls);
        out.put("missingRequiredValues", missingRequiredValues);
        out.put("categoryDistribution", categoryDistribution);
        return out;
    }
}
