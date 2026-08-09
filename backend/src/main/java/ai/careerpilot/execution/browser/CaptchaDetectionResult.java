package ai.careerpilot.execution.browser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P0.2 — the outcome of {@link FrameAwareCaptchaCheck}, carrying enough detail to tell an operator
 * (or a test) not just <em>whether</em> a CAPTCHA/login wall was found but <em>where</em>.
 *
 * <p>{@code inaccessibleFrameCount} is reported for honesty only and never contributes to {@link
 * #detected()} — a cross-origin frame that could not be read is a fact about a browser security
 * boundary, not evidence of a CAPTCHA. Fabricating a positive from an absence of access would be
 * exactly the kind of invented signal this platform's evidence model exists to prevent (see {@code
 * VerificationAdjudicator}'s own discipline of never inventing a signal it cannot produce).
 *
 * @param detected              {@code topLevelDetected || frameDetected} — the single boolean every
 *                              call site actually branches on
 * @param topLevelDetected      the pre-existing {@code CaptchaLoginDetector} signal, unchanged
 * @param frameDetected         a CAPTCHA marker found in a same-origin <b>child</b> frame — the new
 *                              signal this class adds; deliberately excludes the top document (that
 *                              is what {@code topLevelDetected} already covers) so the two never
 *                              double-count the same evidence
 * @param detectedFramePath     the frame path that tripped {@code frameDetected}, or {@code null}
 * @param framesInspected       same-origin frames (including the top document) actually read
 * @param inaccessibleFrameCount cross-origin frames the browser refused to read into — reported,
 *                              never treated as a positive CAPTCHA signal
 * @param reason                a human-readable summary for logs/timeline evidence
 */
public record CaptchaDetectionResult(boolean detected, boolean topLevelDetected, boolean frameDetected,
                                     String detectedFramePath, int framesInspected,
                                     int inaccessibleFrameCount, String reason) {

    public static CaptchaDetectionResult clean(int framesInspected, int inaccessibleFrameCount) {
        return new CaptchaDetectionResult(false, false, false, null, framesInspected,
                inaccessibleFrameCount, "no CAPTCHA or login wall detected");
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detected", detected);
        out.put("topLevelDetected", topLevelDetected);
        out.put("frameDetected", frameDetected);
        out.put("detectedFramePath", detectedFramePath);
        out.put("framesInspected", framesInspected);
        out.put("inaccessibleFrameCount", inaccessibleFrameCount);
        out.put("reason", reason);
        return out;
    }
}
