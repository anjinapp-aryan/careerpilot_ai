package ai.careerpilot.execution.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap D — pure unit tests for the safety-critical CAPTCHA/login-wall trip-wire. No browser is ever
 * launched here (not possible in this environment); this is the entire test surface for the
 * detection logic, exercised directly against raw HTML strings.
 */
class CaptchaLoginDetectorTest {

    @Test
    void nullOrBlankHtmlIsNeverAMatch() {
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin(null)).isFalse();
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin("")).isFalse();
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin("   ")).isFalse();
    }

    @Test
    void plainApplyFormIsNotAMatch() {
        String html = "<form><input name=\"email\" type=\"text\"><input name=\"first_name\"></form>";
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin(html)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<div class=\"g-recaptcha\" data-sitekey=\"x\"></div>",
            "<iframe src=\"https://www.google.com/recaptcha/api2/anchor\"></iframe>",
            "<script src=\"https://www.RECAPTCHA.net/recaptcha/api.js\"></script>"
    })
    void recaptchaMarkersAreDetected(String html) {
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin(html)).isTrue();
    }

    @Test
    void hcaptchaMarkerIsDetected() {
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin(
                "<div class=\"h-captcha\" data-sitekey=\"x\"></div>")).isTrue();
    }

    @Test
    void turnstileMarkerIsDetected() {
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin(
                "<div class=\"cf-turnstile\" data-sitekey=\"x\"></div>")).isTrue();
    }

    @Test
    void passwordInputIsDetectedAsALoginWall() {
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin(
                "<form><input type=\"password\" name=\"pwd\"></form>")).isTrue();
    }

    @Test
    void passwordInputWithSingleQuotesIsDetected() {
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin(
                "<input type='password' name='pwd'>")).isTrue();
    }

    @Test
    void detectionIsCaseInsensitive() {
        assertThat(CaptchaLoginDetector.looksLikeCaptchaOrLogin("<INPUT TYPE=\"PASSWORD\">")).isTrue();
    }
}
