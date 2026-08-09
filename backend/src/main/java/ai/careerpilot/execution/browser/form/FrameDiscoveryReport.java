package ai.careerpilot.execution.browser.form;

import ai.careerpilot.execution.browser.validation.AtsPageVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complete result of {@link FormDiscoveryScript#DISCOVER_FRAME_REPORT} — every same-origin
 * frame (including the top document) inspected for a form and a CAPTCHA, plus every frame the
 * browser refused to read into.
 *
 * <p><b>Honesty over completeness.</b> A frame that could not be inspected (cross-origin) is
 * recorded as {@link InaccessibleFrame}, never silently dropped or treated as "no form here" — those
 * are different findings, and conflating them is exactly how a real form goes unreported without an
 * operator ever learning why.
 *
 * @param frames        every same-origin document reached, including the top one ({@code framePath}
 *                      empty)
 * @param inaccessible  every {@code <iframe>}/{@code <frame>} element seen whose document could not
 *                      be read (cross-origin — the browser throws by design, and this is never
 *                      worked around)
 */
public record FrameDiscoveryReport(List<FramePageSignals> frames, List<InaccessibleFrame> inaccessible) {

    public FrameDiscoveryReport {
        frames = frames == null ? List.of() : List.copyOf(frames);
        inaccessible = inaccessible == null ? List.of() : List.copyOf(inaccessible);
    }

    /** A same-origin frame element whose content document threw on access. */
    public record InaccessibleFrame(String parentFramePath, String src) {
        public InaccessibleFrame {
            parentFramePath = parentFramePath == null ? "" : parentFramePath;
            src = src == null ? "" : src;
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("parentFramePath", parentFramePath);
            out.put("src", src);
            return out;
        }
    }

    public static FrameDiscoveryReport empty() {
        return new FrameDiscoveryReport(List.of(), List.of());
    }

    /** Every {@code <iframe>} element seen, accessible or not — the true count of frames on the page. */
    public int framesDiscovered() {
        // frames includes the top document itself, which is not an <iframe> element — excluded here
        // so the count answers "how many iframe elements were on the page", matching the pre-existing
        // PageEnvironment.iframeCount() this report is meant to reconcile with, not duplicate.
        return (int) frames.stream().filter(f -> !f.isTopDocument()).count() + inaccessible.size();
    }

    /** Frames whose document was actually read (same-origin), top document included. */
    public int framesInspected() {
        return frames.size();
    }

    public int inaccessibleFrameCount() {
        return inaccessible.size();
    }

    public List<FramePageSignals> framesWithForms() {
        return frames.stream().filter(f -> f.score() >= AtsPageVerifier.MINIMUM_SIGNAL_SCORE).toList();
    }

    /** The best-scoring frame that meets the form threshold, preferring the top document on a tie
     * (a form on the top document is drivable without any frame-locator work at all). */
    public Optional<FramePageSignals> bestForm() {
        return framesWithForms().stream()
                .max((a, b) -> {
                    int byScore = Integer.compare(a.score(), b.score());
                    if (byScore != 0) return byScore;
                    // Prefer the top document (isTopDocument true sorts higher).
                    return Boolean.compare(a.isTopDocument(), b.isTopDocument());
                });
    }

    /** The first frame (top document included) reporting a CAPTCHA marker, if any. */
    public Optional<FramePageSignals> captchaFrame() {
        return frames.stream().filter(FramePageSignals::captchaDetected).findFirst();
    }

    public boolean anyCaptchaDetected() {
        return captchaFrame().isPresent();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("framesDiscovered", framesDiscovered());
        out.put("framesInspected", framesInspected());
        out.put("framesWithForms", framesWithForms().size());
        out.put("inaccessibleFrames", inaccessibleFrameCount());
        out.put("captchaDetectedFrame", captchaFrame().map(FramePageSignals::framePath)
                .map(p -> p.isEmpty() ? "(top document)" : p).orElse(null));
        out.put("formFrame", bestForm().map(FramePageSignals::framePath)
                .map(p -> p.isEmpty() ? "(top document)" : p).orElse(null));
        return out;
    }
}
