package ai.careerpilot.execution.browser;

import ai.careerpilot.execution.browser.form.FormDiscoveryScript;
import ai.careerpilot.execution.browser.form.FrameDiscoveryReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P0.2 — the shared, iframe-aware CAPTCHA/login-wall check for every real (non-validation) browser
 * execution path: {@code GuestApplyAutomationService} and {@code MultiStepExecutionOrchestrator}.
 *
 * <h2>What this closes</h2>
 * Validation (Phase 12C.5 + this session's iframe-aware fix) already detects a CAPTCHA sitting only
 * inside a same-origin iframe. Production execution never did — every one of its four call sites
 * called {@code CaptchaLoginDetector.looksLikeCaptchaOrLogin(browser.currentPageHtml())}, which reads
 * only the top document's serialised HTML and cannot see into an iframe's own document. A CAPTCHA
 * embedded solely inside a frame was therefore invisible to real execution even after validation
 * learned to see it.
 *
 * <h2>Why this reuses rather than reimplements</h2>
 * {@code CaptchaLoginDetector} is untouched — its top-level check still runs, unchanged. The frame
 * traversal is the exact same read-only {@link FormDiscoveryScript#DISCOVER_FRAME_REPORT} probe the
 * validation harness already uses via the existing generic {@code
 * PlaywrightAutomationProvider#evaluate(String)} — no new Playwright primitive, no second traversal
 * implementation. This class only composes the two existing signals into one decision.
 *
 * <h2>Never a false negative from missing access</h2>
 * A cross-origin frame the browser refuses to read into is recorded in {@link
 * CaptchaDetectionResult#inaccessibleFrameCount()} for honesty, but never makes {@link
 * CaptchaDetectionResult#detected()} true on its own — that would be inventing a signal from an
 * absence of evidence, which this platform never does (see {@code VerificationAdjudicator}'s
 * identical discipline for submission evidence). {@code detected} only ever reflects an actual
 * positive marker found somewhere the browser could genuinely read.
 */
public final class FrameAwareCaptchaCheck {

    private static final Logger log = LoggerFactory.getLogger(FrameAwareCaptchaCheck.class);

    private FrameAwareCaptchaCheck() {
    }

    /**
     * Runs both checks against whatever page {@code browser} currently has open. Never throws — a
     * probe failure degrades to "not detected from this signal" for that one signal only, exactly
     * the same fail-open-per-signal discipline {@code BrowserValidationHarness} already uses; the
     * other signal (if it succeeded) still stands.
     */
    public static CaptchaDetectionResult run(PlaywrightAutomationProvider browser) {
        boolean topLevel = false;
        try {
            topLevel = CaptchaLoginDetector.looksLikeCaptchaOrLogin(browser.currentPageHtml());
        } catch (Exception e) {
            log.debug("FRAME_AWARE_CAPTCHA top-level probe failed: {}", e.toString());
        }

        FrameDiscoveryReport frames = FrameDiscoveryReport.empty();
        try {
            frames = FormDiscoveryScript.parseFrameReport(
                    browser.evaluate(FormDiscoveryScript.DISCOVER_FRAME_REPORT));
        } catch (Exception e) {
            log.debug("FRAME_AWARE_CAPTCHA frame probe failed: {}", e.toString());
        }

        // Excludes the top document deliberately: that evidence is already carried by `topLevel`
        // above (a different detector, same document), so counting it again here would not add a
        // new finding — only a child frame is genuinely new information.
        String framePath = frames.frames().stream()
                .filter(f -> !f.isTopDocument() && f.captchaDetected())
                .map(f -> f.framePath())
                .findFirst().orElse(null);
        boolean frameDetected = framePath != null;

        boolean detected = topLevel || frameDetected;
        String reason;
        if (!detected) {
            reason = "no CAPTCHA or login wall detected (top document + " + frames.framesInspected()
                    + " same-origin frame(s) inspected)";
        } else if (topLevel && frameDetected) {
            reason = "CAPTCHA/login wall detected on the top document and in frame \"" + framePath + "\"";
        } else if (topLevel) {
            reason = "CAPTCHA/login wall detected on the top document";
        } else {
            reason = "CAPTCHA detected inside same-origin frame \"" + framePath + "\"";
        }

        return new CaptchaDetectionResult(detected, topLevel, frameDetected, framePath,
                frames.framesInspected(), frames.inaccessibleFrameCount(), reason);
    }
}
