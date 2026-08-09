package ai.careerpilot.execution.browser.form;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Iframe-aware form discovery — one frame's (or the top document's) structural form signals and
 * CAPTCHA presence, as read by {@link FormDiscoveryScript#DISCOVER_FRAME_REPORT}.
 *
 * <p>Mirrors {@code AtsPageVerifier.FormSignals}' scoring exactly (same five-point rubric, same
 * {@link #score()} formula) but scoped to one frame rather than the top document only — that
 * scoping is the entire fix: {@code AtsPageVerifier} and {@code DISCOVER_ENVIRONMENT} historically
 * only ever saw the top-level document, so a genuine Greenhouse/Lever form sitting inside an iframe
 * scored 0 and was rejected as {@code INVALID_APPLICATION_PAGE} before the iframe-capable {@code
 * DISCOVER_FIELDS} script (which already walks same-origin frames — see its own Phase B notes) ever
 * ran.
 *
 * @param framePath        empty for the top document, otherwise the same-origin frame chain (same
 *                         convention as {@link DiscoveredField#framePath()})
 * @param frameUrl         {@code location.href} of the frame's own document — same-origin only, so
 *                         always readable when this record exists at all
 * @param title            the frame document's {@code title}
 * @param captchaDetected  a CAPTCHA provider marker found in this frame's own markup — reported,
 *                         never solved. A CAPTCHA sitting only inside an iframe was previously
 *                         invisible: the pre-existing {@code DISCOVER_ENVIRONMENT} regex and {@code
 *                         CaptchaLoginDetector} both operate on the top document's HTML only.
 */
public record FramePageSignals(String framePath, String frameUrl, String title,
                               int fileInputs, int emailInputs, int textInputs, int submitButtons,
                               boolean applicationHeading, boolean passwordInputs,
                               boolean captchaDetected) {

    public FramePageSignals {
        framePath = framePath == null ? "" : framePath;
        frameUrl = frameUrl == null ? "" : frameUrl;
        title = title == null ? "" : title;
    }

    public boolean isTopDocument() {
        return framePath.isEmpty();
    }

    /** Identical rubric to {@code AtsPageVerifier.FormSignals#score()} — deliberately duplicated
     * rather than shared, since the two types live in different packages for a reason (form
     * identity vs. discovery); see each type's own javadoc. */
    public int score() {
        int s = 0;
        if (fileInputs > 0) s++;
        if (emailInputs > 0) s++;
        if (textInputs >= 2) s++;
        if (submitButtons > 0) s++;
        if (applicationHeading) s++;
        return s;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("framePath", framePath);
        out.put("frameUrl", frameUrl);
        out.put("title", title);
        out.put("score", score());
        out.put("captchaDetected", captchaDetected);
        return out;
    }
}
