package ai.careerpilot.execution.browser;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Gap D — pure, dependency-free CAPTCHA/login-wall detection over raw page HTML. Deliberately
 * extracted out of {@link PlaywrightAutomationProvider} so the risk-relevant logic is unit
 * testable without launching a real browser (not possible in this build/CI environment).
 *
 * <p>SAFETY-CRITICAL: this is the trip-wire that makes {@link GuestApplyAutomationService} abort
 * and fall back to human review instead of ever attempting to solve or work around a CAPTCHA or a
 * credentialed login form. Detection is intentionally simple and conservative (a few well-known
 * markers) — a false positive (aborting when it wasn't actually needed) is the safe failure mode;
 * a false negative is not acceptable, so do not narrow these patterns without care.
 */
public final class CaptchaLoginDetector {

    private static final Pattern RECAPTCHA = Pattern.compile("recaptcha", Pattern.CASE_INSENSITIVE);
    private static final Pattern HCAPTCHA = Pattern.compile("h-?captcha", Pattern.CASE_INSENSITIVE);
    private static final Pattern TURNSTILE = Pattern.compile("cf-turnstile|turnstile", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSWORD_INPUT = Pattern.compile(
            "<input[^>]*type\\s*=\\s*['\"]password['\"]", Pattern.CASE_INSENSITIVE);

    private CaptchaLoginDetector() {}

    /**
     * True when the page HTML contains a recognizable CAPTCHA widget marker or a password input
     * field (a login/account-creation wall). Null/blank HTML is never treated as a match.
     */
    public static boolean looksLikeCaptchaOrLogin(String pageHtml) {
        if (pageHtml == null || pageHtml.isBlank()) return false;
        String h = pageHtml.toLowerCase(Locale.ROOT);
        if (RECAPTCHA.matcher(h).find()) return true;
        if (HCAPTCHA.matcher(h).find()) return true;
        if (TURNSTILE.matcher(h).find()) return true;
        return PASSWORD_INPUT.matcher(h).find();
    }
}
