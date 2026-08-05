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
    private final ai.careerpilot.execution.browser.pool.BrowserLeasePool leasePool;
    private final BrowserAutomationMetrics metrics;
    private final boolean enabled;

    /**
     * The per-thread lease. The {@link BrowserAutomationProvider} interface carries no session
     * handle, so thread affinity remains the binding contract — but the held object is now a
     * pool-tracked {@link ai.careerpilot.execution.browser.pool.ContextLease} rather than a raw
     * context, which means an abandoned session is reclaimable instead of leaked forever.
     */
    private final ThreadLocal<ai.careerpilot.execution.browser.pool.ContextLease> leaseHolder = new ThreadLocal<>();

    public PlaywrightAutomationProvider(BrowserSessionManager sessionManager,
                                        ai.careerpilot.execution.browser.pool.BrowserLeasePool leasePool,
                                        BrowserAutomationMetrics metrics,
                                        @Value("${browser.automation.enabled:false}") boolean enabled) {
        this.sessionManager = sessionManager;
        this.leasePool = leasePool;
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

        // LEAK GUARD. Previously this overwrote the thread's context holder unconditionally, so a
        // caller that threw between navigate() and logout() left the old context open forever AND
        // never decremented the open-context counter — on a pooled thread the next job silently
        // orphaned its predecessor's browser. Releasing here makes the sequence self-healing.
        ai.careerpilot.execution.browser.pool.ContextLease stale = leaseHolder.get();
        if (stale != null) {
            log.warn("PLAYWRIGHT_PROVIDER navigate() found an unreleased lease {} on thread {} — "
                    + "releasing it before starting a new session", stale.id(), Thread.currentThread().getName());
            safeRelease(stale);
            leaseHolder.remove();
        }

        // Bounded acquire — this is the memory gate. Throws BrowserCapacityUnavailableException
        // rather than queueing without limit, so pressure surfaces as a retryable failure instead
        // of an OOM.
        ai.careerpilot.execution.browser.pool.ContextLease lease = leasePool.acquire();
        leaseHolder.set(lease);
        try {
            Page page = lease.page();
            withRetry(() -> {
                page.navigate(url, new Page.NavigateOptions().setTimeout(ACTION_TIMEOUT.toMillis()));
                return null;
            });
        } catch (RuntimeException e) {
            // Never hold capacity for a session that failed before it began.
            safeRelease(lease);
            leaseHolder.remove();
            throw e;
        }
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

    /**
     * Ends the session and returns the browser capacity to the pool. Safe to call when no session
     * is active (the guest-apply flow calls it from a {@code finally}), and safe to call twice —
     * {@link ai.careerpilot.execution.browser.pool.ContextLease#close()} is idempotent.
     */
    @Override
    public void logout() {
        ai.careerpilot.execution.browser.pool.ContextLease lease = leaseHolder.get();
        if (lease == null) return;
        safeRelease(lease);
        leaseHolder.remove();
    }

    // ── Phase 12C — Universal Form Automation primitives ──────────────────────────────────────
    //
    // Added to this class rather than to BrowserAutomationProvider on purpose. The interface is the
    // narrow, ATS-agnostic contract the pre-existing connector flow depends on; widening it would
    // force every future implementation to support the full universal-form surface. The only two
    // callers of these (GuestApplyAutomationService, BrowserFormAutomationEngine) already depend on
    // this concrete type, so nothing is gained by the indirection and the interface stays stable.
    //
    // Every one of these operates on an explicit selector produced by FormDiscoveryScript, and
    // every one is wrapped in the same bounded withRetry() as the pre-existing operations, so a
    // transient AJAX re-render is survivable without a new retry mechanism.

    /**
     * Runs a read-only discovery function in the page and returns its raw result for
     * {@code FormDiscoveryScript} to parse. Deliberately not a general-purpose script escape hatch:
     * the three constants in that class are the only intended arguments, and all three query
     * without mutating.
     */
    public Object evaluate(String script) {
        return withRetry(() -> page().evaluate(script));
    }

    /** The page's current URL, used to detect whether a submit actually navigated. */
    public String currentUrl() {
        return page().url();
    }

    // ── Phase 12C.5 — validation-harness support ───────────────────────────────────────────────

    /** JS errors observed on the current page, collected only while capture is enabled. */
    private final ThreadLocal<java.util.List<String>> consoleErrors = ThreadLocal.withInitial(java.util.ArrayList::new);

    /**
     * Registers listeners for JavaScript errors and console errors on the current page.
     *
     * <p>Must be called <em>after</em> {@link #navigate} opens the lease but before waiting for the
     * page to settle, which is where SPA hydration errors surface. <b>Errors thrown during the
     * initial document parse, before this runs, are not captured</b> — stated rather than papered
     * over, because a zero count here means "none observed after we started looking", not "none
     * occurred".
     */
    public void startConsoleCapture() {
        java.util.List<String> sink = consoleErrors.get();
        sink.clear();
        Page page = page();
        page.onPageError(error -> sink.add("pageerror: " + truncate(error)));
        page.onConsoleMessage(message -> {
            if ("error".equalsIgnoreCase(message.type())) {
                sink.add("console.error: " + truncate(message.text()));
            }
        });
    }

    /** Everything collected since {@link #startConsoleCapture}, then clears the buffer. */
    public java.util.List<String> drainConsoleErrors() {
        java.util.List<String> sink = consoleErrors.get();
        java.util.List<String> copy = java.util.List.copyOf(sink);
        sink.clear();
        return copy;
    }

    /**
     * Waits for the page to stop changing before anything reads it.
     *
     * <p>This is the difference between discovering a real form and discovering an empty
     * {@code <div id="app">}. Two stages, because neither alone is sufficient on a modern ATS:
     * network-idle catches the XHR that fetches the posting, and a short settle window after it
     * catches the client-side render that follows. A {@code load} event fires long before either.
     *
     * <p>Never throws: a page that never reaches network-idle (long-poll, analytics beacon, open
     * websocket) is common and is not a failure — discovery simply proceeds against whatever has
     * rendered, and the report says how long it waited.
     */
    public void waitForStable(long settleMs) {
        Page page = page();
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
        } catch (Exception e) {
            log.debug("PLAYWRIGHT_PROVIDER DOMCONTENTLOADED wait skipped: {}", e.toString());
        }
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
        } catch (Exception e) {
            // Expected on pages that hold a connection open. Not an error.
            log.debug("PLAYWRIGHT_PROVIDER network idle not reached: {}", e.toString());
        }
        if (settleMs > 0) {
            try {
                page.waitForTimeout(settleMs);
            } catch (Exception e) {
                log.debug("PLAYWRIGHT_PROVIDER settle wait interrupted: {}", e.toString());
            }
        }
    }

    /** The page title, for the validation report. Never throws. */
    public String pageTitle() {
        try {
            return page().title();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(Object value) {
        String s = String.valueOf(value);
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** Fill one specific control. */
    public void fillAt(String selector, String value) {
        withRetry(() -> {
            page().fill(selector, value == null ? "" : value);
            return null;
        });
    }

    /**
     * Set a contenteditable rich-text editor's content. Uses click-then-type rather than assigning
     * {@code innerHTML}: editors built on ProseMirror/Quill (Lever, Ashby) keep their own model and
     * ignore direct DOM writes, so an innerHTML assignment appears to work and then submits empty.
     */
    public void fillRichText(String selector, String value) {
        withRetry(() -> {
            Page p = page();
            p.click(selector);
            p.fill(selector, "");
            // pressSequentially, not the deprecated type(): real keystrokes are what the editor's
            // own input handlers listen for.
            p.locator(selector).pressSequentially(value == null ? "" : value);
            return null;
        });
    }

    /** Select a dropdown option by its visible label. */
    public void selectOptionAt(String selector, String optionLabel) {
        withRetry(() -> {
            page().selectOption(selector, new com.microsoft.playwright.options.SelectOption().setLabel(optionLabel));
            return null;
        });
    }

    /** Check or uncheck a checkbox/radio. Idempotent — Playwright no-ops when already in state. */
    public void setCheckedAt(String selector, boolean checked) {
        withRetry(() -> {
            page().setChecked(selector, checked);
            return null;
        });
    }

    /** Upload a file to one specific file input. */
    public void uploadFileTo(String selector, Path file) {
        withRetry(() -> {
            page().setInputFiles(selector, file);
            return null;
        });
    }

    /**
     * Whether a file input actually holds a file, read back from the DOM after an upload.
     * <b>This is what makes "upload succeeded" a verified claim rather than an assumption</b> — a
     * {@code setInputFiles} call that silently no-ops (detached element, re-rendered form) would
     * otherwise be reported as success and the employer would receive an application with no resume.
     */
    public boolean hasUploadedFile(String selector) {
        Object result = withRetry(() -> page().evaluate(
                "s => { const el = document.querySelector(s); return !!(el && el.files && el.files.length > 0); }",
                selector));
        return result instanceof Boolean b && b;
    }

    /** The filename a file input reports, for evidence. Null when nothing is attached. */
    public String uploadedFileName(String selector) {
        Object result = withRetry(() -> page().evaluate(
                "s => { const el = document.querySelector(s); return (el && el.files && el.files.length > 0)"
                        + " ? el.files[0].name : null; }",
                selector));
        return result == null ? null : String.valueOf(result);
    }

    /** Click a specific control (multi-step Next/Submit). */
    public void clickAt(String selector) {
        withRetry(() -> {
            page().click(selector);
            return null;
        });
    }

    /**
     * Dismisses cookie/consent banners, which sit above the form and swallow clicks. Best-effort
     * and never retried: a missing banner is the normal case, not a failure, so this must not cost
     * three retry rounds on every run.
     */
    public int dismissOverlays() {
        try {
            Object removed = page().evaluate("""
                    () => {
                      let n = 0;
                      const pat = /(accept|agree|allow|got it|understood|dismiss|close)/i;
                      document.querySelectorAll('button, [role="button"]').forEach(el => {
                        const t = (el.textContent || '').trim();
                        if (!t || t.length > 40 || !pat.test(t)) return;
                        const host = el.closest('[class*="cookie" i], [class*="consent" i], [id*="cookie" i], [id*="consent" i]');
                        if (host) { el.click(); n++; }
                      });
                      return n;
                    }
                    """);
            return removed instanceof Number num ? num.intValue() : 0;
        } catch (Exception e) {
            log.debug("PLAYWRIGHT_PROVIDER overlay dismissal skipped: {}", e.toString());
            return 0;
        }
    }

    // ── helpers ──

    private void safeRelease(ai.careerpilot.execution.browser.pool.ContextLease lease) {
        try {
            lease.close();
        } catch (Exception e) {
            log.warn("PLAYWRIGHT_PROVIDER lease release failed lease={}: {}", lease.id(), e.toString());
        }
    }

    private Page page() {
        ai.careerpilot.execution.browser.pool.ContextLease lease = leaseHolder.get();
        if (lease == null || lease.isReleased()) {
            throw new IllegalStateException("no active page — navigate() must be called first");
        }
        return lease.page();
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
