package ai.careerpilot.execution.browser.form;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Iframe-aware form/CAPTCHA discovery — {@link FormDiscoveryScript#parseFrameReport} and {@link
 * FrameDiscoveryReport}'s own aggregate methods.
 *
 * <p>Self-authored fixtures simulating what {@link FormDiscoveryScript#DISCOVER_FRAME_REPORT} would
 * return from a real page, in the same style as {@code FormDiscoveryScriptParseTest} — no real
 * browser or employer site involved, matching this codebase's established "pure Java over parsed
 * maps" testing discipline.
 */
class FormDiscoveryScriptFrameReportTest {

    private static Map<String, Object> frame(String path, int fileInputs, int emailInputs,
                                              int textInputs, int submitButtons,
                                              boolean applicationHeading, boolean captcha) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("framePath", path);
        m.put("frameUrl", "https://example.com" + path);
        m.put("title", "Apply");
        m.put("fileInputs", fileInputs);
        m.put("emailInputs", emailInputs);
        m.put("textInputs", textInputs);
        m.put("submitButtons", submitButtons);
        m.put("applicationHeading", applicationHeading);
        m.put("passwordInputs", false);
        m.put("captchaDetected", captcha);
        return m;
    }

    private static Map<String, Object> inaccessible(String parentPath, String src) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parentFramePath", parentPath);
        m.put("src", src);
        return m;
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> frames,
                                                List<Map<String, Object>> inaccessible) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frames", frames);
        out.put("inaccessible", inaccessible);
        return out;
    }

    // ── Fixture A: top-level form only — the pre-existing, unaffected case ──

    @Test
    @DisplayName("A: a top-level form parses with one frame entry scoring 5/5")
    void topLevelFormOnly() {
        FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(
                envelope(List.of(frame("", 1, 1, 6, 1, true, false)), List.of()));

        assertThat(report.frames()).hasSize(1);
        assertThat(report.frames().get(0).isTopDocument()).isTrue();
        assertThat(report.frames().get(0).score()).isEqualTo(5);
        assertThat(report.bestForm()).isPresent();
        assertThat(report.bestForm().get().isTopDocument()).isTrue();
        assertThat(report.framesDiscovered()).isZero(); // no <iframe> elements at all
        assertThat(report.inaccessibleFrameCount()).isZero();
    }

    // ── Fixture B: form inside one iframe ──

    @Test
    @DisplayName("B: top document is empty, one child iframe has the real form")
    void formInsideOneIframe() {
        FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(envelope(List.of(
                frame("", 0, 0, 0, 0, false, false),
                frame("iframe:nth-of-type(1)", 1, 1, 6, 1, true, false)
        ), List.of()));

        assertThat(report.framesWithForms()).hasSize(1);
        assertThat(report.bestForm()).isPresent();
        assertThat(report.bestForm().get().framePath()).isEqualTo("iframe:nth-of-type(1)");
        assertThat(report.bestForm().get().isTopDocument()).isFalse();
        assertThat(report.framesDiscovered()).isEqualTo(1);
        assertThat(report.framesInspected()).isEqualTo(2);
    }

    // ── Fixture C: form inside a nested iframe (two levels deep) ──

    @Test
    @DisplayName("C: form sits two iframes deep, still discovered and identified")
    void formInsideNestedIframe() {
        FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(envelope(List.of(
                frame("", 0, 0, 0, 0, false, false),
                frame("iframe:nth-of-type(1)", 0, 0, 0, 0, false, false),
                frame("iframe:nth-of-type(1) >> #inner", 1, 1, 6, 1, true, false)
        ), List.of()));

        assertThat(report.bestForm()).isPresent();
        assertThat(report.bestForm().get().framePath()).isEqualTo("iframe:nth-of-type(1) >> #inner");
        assertThat(report.framesInspected()).isEqualTo(3);
    }

    // ── Fixture D: multiple iframes, only one has a form ──

    @Test
    @DisplayName("D: three frames, only the second has a real form")
    void multipleIframesOnlyOneHasAForm() {
        FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(envelope(List.of(
                frame("", 0, 0, 0, 0, false, false),
                frame("iframe:nth-of-type(1)", 0, 0, 1, 0, false, false), // ad/analytics iframe, low score
                frame("iframe:nth-of-type(2)", 1, 1, 6, 1, true, false)
        ), List.of()));

        assertThat(report.framesWithForms()).hasSize(1);
        assertThat(report.bestForm().get().framePath()).isEqualTo("iframe:nth-of-type(2)");
    }

    // ── Fixture E: CAPTCHA in the main document ──

    @Test
    @DisplayName("E: CAPTCHA marker on the top document is reported as the top document")
    void captchaInMainDocument() {
        FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(
                envelope(List.of(frame("", 1, 1, 6, 1, true, true)), List.of()));

        assertThat(report.anyCaptchaDetected()).isTrue();
        assertThat(report.captchaFrame()).isPresent();
        assertThat(report.captchaFrame().get().isTopDocument()).isTrue();
    }

    // ── Fixture F: CAPTCHA inside an iframe, top document clean ──

    @Test
    @DisplayName("F: CAPTCHA only inside a child iframe is still detected and attributed to it")
    void captchaInsideIframe() {
        FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(envelope(List.of(
                frame("", 0, 0, 0, 0, false, false),
                frame("iframe:nth-of-type(1)", 1, 1, 6, 1, true, true)
        ), List.of()));

        assertThat(report.anyCaptchaDetected()).isTrue();
        assertThat(report.captchaFrame()).isPresent();
        assertThat(report.captchaFrame().get().framePath()).isEqualTo("iframe:nth-of-type(1)");
        assertThat(report.captchaFrame().get().isTopDocument()).isFalse();
    }

    // ── Fixture G: inaccessible / cross-origin iframe ──

    @Test
    @DisplayName("G: a cross-origin iframe is reported honestly, never silently dropped")
    void inaccessibleCrossOriginFrame() {
        FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(envelope(
                List.of(frame("", 0, 0, 0, 0, false, false)),
                List.of(inaccessible("", "https://widget.thirdparty.example/embed"))));

        assertThat(report.inaccessibleFrameCount()).isEqualTo(1);
        assertThat(report.framesDiscovered()).isEqualTo(1);
        assertThat(report.framesWithForms()).isEmpty();
        assertThat(report.bestForm()).isEmpty();
        Map<String, Object> snap = report.snapshot();
        assertThat(snap).containsEntry("inaccessibleFrames", 1);
    }

    // ── parse robustness ──

    @Nested
    @DisplayName("a broken or missing probe never fails the caller")
    class Robustness {

        @Test
        void nullScriptResultDegradesToEmpty() {
            FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(null);

            assertThat(report.frames()).isEmpty();
            assertThat(report.inaccessible()).isEmpty();
            assertThat(report.bestForm()).isEmpty();
            assertThat(report.anyCaptchaDetected()).isFalse();
        }

        @Test
        void nonMapScriptResultDegradesToEmpty() {
            FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport("unexpected string");

            assertThat(report).isEqualTo(FrameDiscoveryReport.empty());
        }

        @Test
        void malformedFrameRowIsSkippedNotFatal() {
            FrameDiscoveryReport report = FormDiscoveryScript.parseFrameReport(
                    envelope(List.of(frame("", 1, 1, 6, 1, true, false), Map.of()), List.of()));

            // The malformed second row still parses (every field defaults honestly), so the real
            // form in the first row is never lost because of it.
            assertThat(report.frames()).hasSizeGreaterThanOrEqualTo(1);
            assertThat(report.bestForm()).isPresent();
        }
    }

    // ── snapshot honesty ──

    @Test
    @DisplayName("snapshot reports null formFrame/captchaDetectedFrame rather than a fabricated value")
    void snapshotReportsHonestNullsWhenNothingFound() {
        Map<String, Object> snap = FrameDiscoveryReport.empty().snapshot();

        assertThat(snap.get("formFrame")).isNull();
        assertThat(snap.get("captchaDetectedFrame")).isNull();
        assertThat(snap).containsEntry("framesDiscovered", 0)
                .containsEntry("framesInspected", 0)
                .containsEntry("framesWithForms", 0)
                .containsEntry("inaccessibleFrames", 0);
    }
}
