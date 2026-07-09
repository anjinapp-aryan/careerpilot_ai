package ai.careerpilot.execution.browser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * Phase 2E.2 — INERT STUB. Implements {@link BrowserAutomationProvider} so the execution engine and
 * DI graph are fully wired, but performs NO real browser automation: the {@code com.microsoft.playwright}
 * dependency is intentionally NOT on the classpath, and every action throws
 * {@link UnsupportedOperationException}. This is the highest-risk surface in the product; real
 * Playwright wiring is a separate, later, deliberately-gated phase.
 *
 * <p>{@link #isConfigured()} returns false unless {@code browser.automation.enabled=true} — and even
 * then the actions still throw, because no engine is present. So there is no code path in this build
 * that can drive a browser. Future siblings (not created here): {@code SeleniumAutomationProvider},
 * {@code BrowserUseAutomationProvider}, {@code OpenAIOperatorProvider}.
 */
@Component
public class PlaywrightAutomationProvider implements BrowserAutomationProvider {

    private static final String STUB = "browser automation not wired — Phase 2E inert stub (no Playwright dependency)";

    private final boolean enabled;

    public PlaywrightAutomationProvider(@Value("${browser.automation.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "playwright";
    }

    @Override
    public boolean isConfigured() {
        // Never truly configured in this build: the real engine is absent even when the flag is on.
        return false;
    }

    /** Exposed for diagnostics only — reflects the flag, not actual readiness (which is always false). */
    public boolean flagEnabled() {
        return enabled;
    }

    @Override public void login(String portalUrl, Map<String, String> credentials) { throw stub(); }
    @Override public void navigate(String url) { throw stub(); }
    @Override public void uploadResume(Path resumeFile) { throw stub(); }
    @Override public void uploadCoverLetter(Path coverLetterFile) { throw stub(); }
    @Override public void fillForm(Map<String, String> fields) { throw stub(); }
    @Override public void answerQuestions(Map<String, String> questionsToAnswers) { throw stub(); }
    @Override public String submit() { throw stub(); }
    @Override public void captureScreenshot(Path out) { throw stub(); }
    @Override public String captureConfirmation() { throw stub(); }
    @Override public void logout() { throw stub(); }

    private static UnsupportedOperationException stub() {
        return new UnsupportedOperationException(STUB);
    }
}
