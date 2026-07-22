package ai.careerpilot.execution.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Gap D — real headless-Chromium implementation of {@link BrowserAutomationProvider} via
 * Playwright's Java API, replacing the earlier inert stub. HIGH RISK — read the class-level safety
 * notes carefully before changing anything here.
 *
 * <p><b>Guest/no-login apply flows ONLY.</b> {@link #login} is intentionally left throwing
 * {@link UnsupportedOperationException} — this build never stores, enters, or handles any
 * credential for any ATS or employer portal. That is not a TODO; it is the safety boundary.
 *
 * <p><b>Session isolation.</b> Each logical "session" (one {@link #navigate} call through to the
 * matching {@link #logout}) gets its own {@link BrowserContext} from {@link BrowserSessionManager},
 * closed in {@link #logout}. State is held per calling thread (a {@link ThreadLocal}) because the
 * {@link BrowserAutomationProvider} interface carries no explicit session handle; callers
 * (currently only {@link GuestApplyAutomationService}, invoked from the dedicated bounded
 * browser/execution executors) must run one execution's full navigate→...→logout sequence on a
 * single thread and must always reach {@link #logout} (try/finally) so a context is never leaked
 * or reused across users/executions.
 *
 * <p><b>CAPTCHA / login-wall detection</b> lives in the pure, unit-tested {@link
 * CaptchaLoginDetector} and is checked by {@link GuestApplyAutomationService} immediately after
 * navigation, before any field is touched — this class merely exposes {@link #currentPageHtml()}
 * so that check can run without embedding detection logic in the Playwright-calling code itself.
 */
@Component
public class PlaywrightAutomationProvider implements BrowserAutomationProvider {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightAutomationProvider.class);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);

    private final BrowserSessionManager sessionManager;
    private final BrowserAutomationMetrics metrics;
    private final boolean enabled;

    private final ThreadLocal<BrowserContext> contextHolder = new ThreadLocal<>();
    private final ThreadLocal<Page> pageHolder = new ThreadLocal<>();

    public PlaywrightAutomationProvider(BrowserSessionManager sessionManager,
                                        BrowserAutomationMetrics metrics,
                                        @Value("${browser.automation.enabled:false}") boolean enabled) {
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "playwright";
    }

    @Override
    public boolean isConfigured() {
        return enabled && sessionManager.isEnabled();
    }

    /** Exposed for diagnostics — same visible-but-not-controlling precedent as WellfoundProvider. */
    public boolean flagEnabled() {
        return enabled;
    }

    @Override
    public void login(String portalUrl, Map<String, String> credentials) {
        throw new UnsupportedOperationException(
                "guest-apply-only build: credentialed login is never performed for any ATS/portal");
    }

    @Override
    public void navigate(String url) {
        requireConfigured();
        BrowserContext ctx = sessionManager.newContext();
        contextHolder.set(ctx);
        Page page = ctx.newPage();
        page.setDefaultTimeout(ACTION_TIMEOUT.toMillis());
        pageHolder.set(page);
        withRetry(() -> {
            page.navigate(url, new Page.NavigateOptions().setTimeout(ACTION_TIMEOUT.toMillis()));
            return null;
        });
    }

    /** The current page's full HTML — feed this to {@link CaptchaLoginDetector}. */
    public String currentPageHtml() {
        return page().content();
    }

    @Override
    public void fillForm(Map<String, String> fields) {
        Page page = page();
        if (fields == null) return;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            withRetry(() -> {
                page.fill(field.getKey(), field.getValue() == null ? "" : field.getValue());
                return null;
            });
        }
    }

    @Override
    public void uploadResume(Path resumeFile) {
        withRetry(() -> {
            page().setInputFiles("input[type=file]", resumeFile);
            return null;
        });
    }

    @Override
    public void uploadCoverLetter(Path coverLetterFile) {
        withRetry(() -> {
            page().setInputFiles("input[type=file][name*=cover]", coverLetterFile);
            return null;
        });
    }

    @Override
    public void answerQuestions(Map<String, String> questionsToAnswers) {
        // No free-text screening-question answering is wired in this build (answers must never be
        // fabricated, and this build only fills verifiably real applicant fields — see
        // GuestApplyAutomationService). Intentionally a no-op rather than a throw so a connector
        // that has nothing to answer isn't forced to special-case this call.
    }

    @Override
    public String submit() {
        withRetry(() -> {
            page().click("button[type=submit]");
            return null;
        });
        return null;
    }

    @Override
    public void captureScreenshot(Path out) {
        page().screenshot(new Page.ScreenshotOptions().setPath(out).setFullPage(true));
    }

    @Override
    public String captureConfirmation() {
        return page().content();
    }

    @Override
    public void logout() {
        Page page = pageHolder.get();
        BrowserContext ctx = contextHolder.get();
        try {
            if (page != null) page.close();
        } catch (Exception e) {
            log.warn("PLAYWRIGHT_PROVIDER page close failed: {}", e.toString());
        }
        try {
            if (ctx != null) ctx.close();
        } catch (Exception e) {
            log.warn("PLAYWRIGHT_PROVIDER context close failed: {}", e.toString());
        } finally {
            if (ctx != null) sessionManager.contextClosed();
        }
        pageHolder.remove();
        contextHolder.remove();
    }

    // ── helpers ──

    private Page page() {
        Page page = pageHolder.get();
        if (page == null) {
            throw new IllegalStateException("no active page — navigate() must be called first");
        }
        return page;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("browser automation not configured (flag off or engine unavailable)");
        }
    }

    /** Small bounded retry — 2 retries, 1s backoff, matching this repo's WebClient Retry.backoff(2, 1s) spirit. */
    private <T> T withRetry(Supplier<T> action) {
        RuntimeException last = new IllegalStateException("withRetry: unreachable");
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                metrics.recordFailure();
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_BACKOFF.toMillis() * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }
}
