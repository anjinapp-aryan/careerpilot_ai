package ai.careerpilot.execution.browser;

import ai.careerpilot.execution.browser.form.FormDiscoveryScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0.2 — the shared iframe-aware CAPTCHA check used by every real (non-validation) execution path:
 * {@code GuestApplyAutomationService} (both {@code attemptFill} and {@code finalizeSubmit}) and
 * {@code MultiStepExecutionOrchestrator} (both {@code replay} and {@code observe}).
 *
 * <p>Pure unit tests over a mocked {@link PlaywrightAutomationProvider} — no real browser, matching
 * this codebase's established discipline (see {@code AtsPageVerifierTest}, {@code
 * FormDiscoveryScriptFrameReportTest}).
 */
class FrameAwareCaptchaCheckTest {

    private static Map<String, Object> frame(String path, boolean captcha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("framePath", path);
        m.put("frameUrl", "https://example.com" + path);
        m.put("title", "Apply");
        m.put("fileInputs", 0);
        m.put("emailInputs", 0);
        m.put("textInputs", 0);
        m.put("submitButtons", 0);
        m.put("applicationHeading", false);
        m.put("passwordInputs", false);
        m.put("captchaDetected", captcha);
        return m;
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> frames,
                                                List<Map<String, Object>> inaccessible) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frames", frames);
        out.put("inaccessible", inaccessible);
        return out;
    }

    private PlaywrightAutomationProvider browserWith(String topHtml, Object frameReport) {
        PlaywrightAutomationProvider browser = mock(PlaywrightAutomationProvider.class);
        when(browser.currentPageHtml()).thenReturn(topHtml);
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FRAME_REPORT)).thenReturn(frameReport);
        return browser;
    }

    // ── 1. CAPTCHA top-level → hard stop ──

    @Test
    @DisplayName("1: CAPTCHA on the top document is detected")
    void captchaTopLevel() {
        PlaywrightAutomationProvider browser = browserWith(
                "<html><body><div class=\"g-recaptcha\"></div></body></html>",
                envelope(List.of(frame("", false)), List.of()));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isTrue();
        assertThat(result.topLevelDetected()).isTrue();
        assertThat(result.frameDetected()).isFalse();
    }

    // ── 2. CAPTCHA same-origin iframe → hard stop ──

    @Test
    @DisplayName("2: CAPTCHA only inside a same-origin iframe is still detected")
    void captchaSameOriginIframe() {
        PlaywrightAutomationProvider browser = browserWith(
                "<html><body>clean top document</body></html>",
                envelope(List.of(frame("", false), frame("iframe:nth-of-type(1)", true)), List.of()));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isTrue();
        assertThat(result.topLevelDetected()).isFalse();
        assertThat(result.frameDetected()).isTrue();
        assertThat(result.detectedFramePath()).isEqualTo("iframe:nth-of-type(1)");
    }

    // ── 3. CAPTCHA nested iframe → hard stop ──

    @Test
    @DisplayName("3: CAPTCHA two iframes deep is still detected")
    void captchaNestedIframe() {
        PlaywrightAutomationProvider browser = browserWith(
                "<html><body>clean top document</body></html>",
                envelope(List.of(
                        frame("", false),
                        frame("iframe:nth-of-type(1)", false),
                        frame("iframe:nth-of-type(1) >> #inner", true)), List.of()));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isTrue();
        assertThat(result.detectedFramePath()).isEqualTo("iframe:nth-of-type(1) >> #inner");
    }

    // ── 4. No CAPTCHA anywhere → execution may continue ──

    @Test
    @DisplayName("4: no CAPTCHA anywhere — not detected")
    void noCaptchaAnywhere() {
        PlaywrightAutomationProvider browser = browserWith(
                "<html><body>clean top document</body></html>",
                envelope(List.of(frame("", false), frame("iframe:nth-of-type(1)", false)), List.of()));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isFalse();
        assertThat(result.topLevelDetected()).isFalse();
        assertThat(result.frameDetected()).isFalse();
    }

    // ── 5. Cross-origin inaccessible iframe → never fabricated as CAPTCHA ──

    @Test
    @DisplayName("5: an inaccessible cross-origin iframe is reported, never treated as a positive CAPTCHA signal")
    void inaccessibleFrameNeverFabricatesDetection() {
        PlaywrightAutomationProvider browser = browserWith(
                "<html><body>clean top document</body></html>",
                envelope(List.of(frame("", false)),
                        List.of(Map.of("parentFramePath", "", "src", "https://widget.thirdparty.example/embed"))));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isFalse();
        assertThat(result.inaccessibleFrameCount()).isEqualTo(1);
        assertThat(result.reason()).contains("no CAPTCHA or login wall detected");
    }

    // ── 9 (partial, pure-logic half): existing top-level behaviour is unchanged ──

    @Test
    @DisplayName("9: top-level detection alone (no frame probe available) still fires — matches CaptchaLoginDetector's pre-existing behaviour")
    void topLevelDetectionUnchangedWhenFrameProbeUnavailable() {
        // Mockito default for an unstubbed evaluate(...) call is null — parseFrameReport(null)
        // degrades to FrameDiscoveryReport.empty(), so this exercises exactly what every pre-P0.2
        // production caller looked like before this class existed.
        PlaywrightAutomationProvider browser = mock(PlaywrightAutomationProvider.class);
        when(browser.currentPageHtml()).thenReturn("<html><body><div class=\"g-recaptcha\"></div></body></html>");

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isTrue();
        assertThat(result.topLevelDetected()).isTrue();
        assertThat(result.framesInspected()).isZero();
    }

    // ── robustness ──

    @Test
    @DisplayName("a throwing top-level probe never crashes the check — the frame signal still stands")
    void throwingTopLevelProbeDegradesGracefully() {
        PlaywrightAutomationProvider browser = mock(PlaywrightAutomationProvider.class);
        when(browser.currentPageHtml()).thenThrow(new IllegalStateException("no active page"));
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FRAME_REPORT))
                .thenReturn(envelope(List.of(frame("", false), frame("iframe:nth-of-type(1)", true)), List.of()));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isTrue();
        assertThat(result.frameDetected()).isTrue();
    }

    @Test
    @DisplayName("a throwing frame probe never crashes the check — the top-level signal still stands")
    void throwingFrameProbeDegradesGracefully() {
        PlaywrightAutomationProvider browser = mock(PlaywrightAutomationProvider.class);
        when(browser.currentPageHtml()).thenReturn("<html><body><div class=\"g-recaptcha\"></div></body></html>");
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FRAME_REPORT))
                .thenThrow(new IllegalStateException("page gone"));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isTrue();
        assertThat(result.topLevelDetected()).isTrue();
    }

    @Test
    @DisplayName("both signals throwing degrades to not-detected, never a crash")
    void bothProbesThrowingDegradesToNotDetected() {
        PlaywrightAutomationProvider browser = mock(PlaywrightAutomationProvider.class);
        when(browser.currentPageHtml()).thenThrow(new IllegalStateException("no active page"));
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FRAME_REPORT))
                .thenThrow(new IllegalStateException("page gone"));

        CaptchaDetectionResult result = FrameAwareCaptchaCheck.run(browser);

        assertThat(result.detected()).isFalse();
    }
}
