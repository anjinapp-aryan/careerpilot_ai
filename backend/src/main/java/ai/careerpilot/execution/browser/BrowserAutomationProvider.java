package ai.careerpilot.execution.browser;

import java.nio.file.Path;
import java.util.Map;

/**
 * Phase 2E.2 — the browser-automation abstraction. Deliberately NOT tied to Playwright: the
 * interface is the seam so a future phase can drop in a Selenium, browser-use, or OpenAI-Operator
 * implementation with no change to the {@link ai.careerpilot.execution.execution.ApplicationExecutionService}.
 *
 * <p>HIGH RISK. The only implementation in the 2E build ({@link PlaywrightAutomationProvider}) is an
 * inert stub whose every action throws — nothing here drives a real browser or submits anything.
 * A provider joins the execution path only when {@link #isConfigured()} is true, which no provider
 * returns in this build.
 */
public interface BrowserAutomationProvider {

    /** Stable provider name for audit/attribution (e.g. "playwright"). */
    String name();

    /** True only when the provider is wired AND its feature flag is on. Always false in the 2E build. */
    boolean isConfigured();

    /** Authenticate against the target site/portal. */
    void login(String portalUrl, Map<String, String> credentials);

    /** Navigate to a URL within an authenticated session. */
    void navigate(String url);

    /** Upload the tailored resume file to the current form. */
    void uploadResume(Path resumeFile);

    /** Upload the cover letter file to the current form. */
    void uploadCoverLetter(Path coverLetterFile);

    /** Fill known form fields from a field-name -> value map. */
    void fillForm(Map<String, String> fields);

    /** Answer free-text/screening questions (question -> answer). Answers must never be fabricated. */
    void answerQuestions(Map<String, String> questionsToAnswers);

    /** Submit the completed application. Returns the site's confirmation reference if any. */
    String submit();

    /** Capture a screenshot of the current page to the given path (evidence/audit). */
    void captureScreenshot(Path out);

    /** Capture the post-submit confirmation text/reference for the audit trail. */
    String captureConfirmation();

    /** Tear down the authenticated session. */
    void logout();
}
