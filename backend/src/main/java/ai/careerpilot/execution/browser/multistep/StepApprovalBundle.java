package ai.careerpilot.execution.browser.multistep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase F1 — everything a reviewer needs to decide on ONE page of a multi-step employer form.
 *
 * <p><b>Why this exists.</b> The pre-F1 approval gate showed a screenshot and nothing else. That is
 * sufficient for a single-page form, where the screenshot <em>is</em> the whole application, and
 * insufficient the moment a wizard has more pages: a reviewer approving page 2 of 4 needs to know
 * which page they are on, what was typed, what was skipped, what could not be identified, and where
 * clicking "approve" will take the automation next.
 *
 * <p><b>Nothing is hidden from the reviewer.</b> Skipped, unknown and unsupported controls are
 * reported separately rather than collapsed into one number, for the same reason
 * {@code SelectorCoverage} keeps them apart: they have different causes and different fixes, and a
 * single "N problems" figure teaches a reviewer to stop reading.
 *
 * <p>The bundle is persisted as captured. It is deliberately never recomputed on read — the record
 * of what a human approved must remain what they actually saw, even after the employer changes the
 * page underneath it.
 *
 * @param stepNumber        1-based page number
 * @param totalSteps        total pages when the page genuinely declares it, else {@code null} —
 *                          never an estimate, since a made-up "of 4" would misrepresent progress
 * @param finalStep         the navigator reported this as the terminal page
 * @param pageUrl           the page as captured
 * @param screenshotKey     storage key of the full-page screenshot
 * @param filledValues      canonical field → value actually typed
 * @param detectedControls  every control discovered, by identity
 * @param skippedControls   control identity → why it was left alone
 * @param unknownControls   controls that could not be identified
 * @param unsupportedControls controls the engine cannot drive
 * @param requiredUnresolved required controls with no verified value — the blocking set
 * @param warnings          anything else the reviewer should weigh
 * @param confidence        automation confidence for this page
 * @param navigationTarget  the control that would be clicked on approval, or {@code null}
 * @param navigationAction  ADVANCE / SUBMIT / UNCLEAR
 */
public record StepApprovalBundle(
        int stepNumber,
        Integer totalSteps,
        boolean finalStep,
        String pageUrl,
        String screenshotKey,
        Map<String, String> filledValues,
        List<String> detectedControls,
        Map<String, String> skippedControls,
        List<String> unknownControls,
        List<String> unsupportedControls,
        List<String> requiredUnresolved,
        List<String> warnings,
        Integer confidence,
        String navigationTarget,
        String navigationAction) {

    public StepApprovalBundle {
        filledValues = filledValues == null ? Map.of() : Map.copyOf(filledValues);
        detectedControls = detectedControls == null ? List.of() : List.copyOf(detectedControls);
        skippedControls = skippedControls == null ? Map.of() : Map.copyOf(skippedControls);
        unknownControls = unknownControls == null ? List.of() : List.copyOf(unknownControls);
        unsupportedControls = unsupportedControls == null ? List.of() : List.copyOf(unsupportedControls);
        requiredUnresolved = requiredUnresolved == null ? List.of() : List.copyOf(requiredUnresolved);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * True when this page is fully resolved. A reviewer may still reject an approvable page — this
     * only says nothing is <em>missing</em>, never that approval is implied.
     */
    public boolean approvable() {
        return requiredUnresolved.isEmpty();
    }

    /**
     * The reviewer payload. Contains the candidate's own values, so it is only ever returned to the
     * owning user — never to the unauthenticated diagnostics surface.
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stepNumber", stepNumber);
        out.put("totalSteps", totalSteps);
        out.put("finalStep", finalStep);
        out.put("pageUrl", pageUrl);
        out.put("screenshotKey", screenshotKey);
        out.put("filledValues", filledValues);
        out.put("detectedControls", detectedControls);
        out.put("skippedControls", skippedControls);
        out.put("unknownControls", unknownControls);
        out.put("unsupportedControls", unsupportedControls);
        out.put("requiredUnresolved", requiredUnresolved);
        out.put("warnings", warnings);
        out.put("confidence", confidence);
        out.put("navigationTarget", navigationTarget);
        out.put("navigationAction", navigationAction);
        out.put("approvable", approvable());
        return out;
    }
}
