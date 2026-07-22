package ai.careerpilot.execution.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gap D — owns the process-wide Playwright/Chromium lifecycle. One shared {@link Playwright} and
 * headless {@link Browser} instance at the Spring bean level (expensive to start; safe to share),
 * lazily launched on first use so a build with {@code browser.automation.enabled=false} never
 * starts a browser process at all. Every caller gets its own isolated {@link BrowserContext} via
 * {@link #newContext()} — contexts are cheap, hold no shared cookies/storage, and MUST be closed by
 * the caller after use (never reused across users/executions; see
 * {@link PlaywrightAutomationProvider}).
 */
@Component
public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);

    private static final int ZOMBIE_CONTEXT_THRESHOLD = 5;
    private static final Duration ZOMBIE_AGE_THRESHOLD = Duration.ofMinutes(5);

    private final boolean enabled;

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final Object lock = new Object();

    // ── Phase 7.16.3 — zombie detection. Deliberately coarse: a process-wide open-context counter
    // and the timestamp of the last-opened context, not a per-context registry — sufficient to
    // detect "something is leaking contexts" or "a context has been open far longer than any real
    // guest-apply attempt should take" without the bookkeeping cost of tracking every context. ──
    private final AtomicInteger openContextCount = new AtomicInteger();
    private volatile Instant lastContextOpenedAt;

    public BrowserSessionManager(@Value("${browser.automation.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Lazily launches headless Chromium on first call. Throws if the flag is off. */
    private Browser browser() {
        if (!enabled) {
            throw new IllegalStateException("browser automation disabled (browser.automation.enabled=false)");
        }
        Browser b = browser;
        if (b == null) {
            synchronized (lock) {
                b = browser;
                if (b == null) {
                    playwright = Playwright.create();
                    browser = b = playwright.chromium().launch(
                            new BrowserType.LaunchOptions().setHeadless(true));
                    log.info("BROWSER_SESSION_MANAGER launched headless chromium");
                }
            }
        }
        return b;
    }

    /** A brand-new, isolated browser context — no shared cookies/storage with any other execution. */
    public BrowserContext newContext() {
        BrowserContext ctx = browser().newContext();
        openContextCount.incrementAndGet();
        lastContextOpenedAt = Instant.now();
        return ctx;
    }

    /** Phase 7.16.3 — called by {@code PlaywrightAutomationProvider#logout} once its context is closed. */
    public void contextClosed() {
        openContextCount.updateAndGet(c -> Math.max(0, c - 1));
    }

    /**
     * Phase 7.16.3 — a coarse health signal: too many contexts open at once, or a context open far
     * longer than any real guest-apply attempt should take, suggests the shared {@link Browser} is
     * stuck/leaking rather than that any single execution is just slow.
     */
    public boolean isZombie() {
        int open = openContextCount.get();
        if (open >= ZOMBIE_CONTEXT_THRESHOLD) return true;
        Instant last = lastContextOpenedAt;
        return open > 0 && last != null && Duration.between(last, Instant.now()).compareTo(ZOMBIE_AGE_THRESHOLD) > 0;
    }

    /** Restarts the shared browser+playwright process only if {@link #isZombie()}. Returns whether it restarted. */
    public boolean restartIfZombie() {
        if (!isZombie()) return false;
        synchronized (lock) {
            try {
                if (browser != null) browser.close();
            } catch (Exception e) {
                log.warn("BROWSER_SESSION_MANAGER zombie-restart browser close failed: {}", e.toString());
            }
            try {
                if (playwright != null) playwright.close();
            } catch (Exception e) {
                log.warn("BROWSER_SESSION_MANAGER zombie-restart playwright close failed: {}", e.toString());
            }
            browser = null;
            playwright = null;
            openContextCount.set(0);
        }
        log.warn("BROWSER_SESSION_MANAGER restarted due to zombie detection (stuck/leaking contexts)");
        return true;
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            try {
                if (browser != null) browser.close();
            } catch (Exception e) {
                log.warn("BROWSER_SESSION_MANAGER browser close failed: {}", e.toString());
            }
            try {
                if (playwright != null) playwright.close();
            } catch (Exception e) {
                log.warn("BROWSER_SESSION_MANAGER playwright close failed: {}", e.toString());
            }
            browser = null;
            playwright = null;
            openContextCount.set(0);
        }
    }
}
