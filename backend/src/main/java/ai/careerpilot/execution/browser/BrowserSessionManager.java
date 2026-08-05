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
    private final ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory launchOptions;
    private final BrowserLifecycleMetrics lifecycleMetrics;

    /**
     * Phase 12B — resolved lazily via {@link org.springframework.beans.factory.ObjectProvider}
     * because {@code BrowserLeasePool} depends on <em>this</em> bean; a constructor reference would
     * be a cycle. This is the same optional/late-collaborator pattern used elsewhere in this
     * codebase (e.g. {@code CareerContextService}'s flag-gated subsystems).
     */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.careerpilot.execution.browser.pool.BrowserLeasePool> leasePoolProvider;

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final Object lock = new Object();

    // ── Phase 7.16.3 — zombie detection. Deliberately coarse: a process-wide open-context counter
    // and the timestamp of the last-opened context, not a per-context registry — sufficient to
    // detect "something is leaking contexts" or "a context has been open far longer than any real
    // guest-apply attempt should take" without the bookkeeping cost of tracking every context. ──
    private final AtomicInteger openContextCount = new AtomicInteger();
    private volatile Instant lastContextOpenedAt;

    public BrowserSessionManager(@Value("${browser.automation.enabled:false}") boolean enabled,
                                 ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory launchOptions,
                                 BrowserLifecycleMetrics lifecycleMetrics,
                                 org.springframework.beans.factory.ObjectProvider<
                                         ai.careerpilot.execution.browser.pool.BrowserLeasePool> leasePoolProvider) {
        this.enabled = enabled;
        this.launchOptions = launchOptions;
        this.lifecycleMetrics = lifecycleMetrics;
        this.leasePoolProvider = leasePoolProvider;
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
                    // Enterprise Browser Automation — launch configuration now comes from
                    // BrowserLaunchOptionsFactory. The previous `new LaunchOptions().setHeadless(true)`
                    // could not start at all on the real deployment target: Docker's 64 MB /dev/shm
                    // crashes renderers, the container has no user namespace for the setuid sandbox,
                    // and Playwright ships no Chromium for linux-arm64. See that class for the
                    // rationale behind every flag.
                    //
                    // Phase 12B — launch is now instrumented. Before this, a Chromium that could
                    // not start produced a stack trace and nothing else: no counter, no last-error
                    // string, nothing on any diagnostics endpoint. On the real deployment target
                    // (ARM, distro Chromium, no sandbox) launch failure is the single most likely
                    // first-time failure mode, so it is the one that most needed a signal.
                    lifecycleMetrics.recordLaunchAttempt();
                    long launchStart = System.currentTimeMillis();
                    try {
                        playwright = Playwright.create();
                        BrowserType.LaunchOptions options = launchOptions.create();
                        browser = b = playwright.chromium().launch(options);
                    } catch (RuntimeException | Error e) {
                        // Partial state is worse than none: a created Playwright with no Browser
                        // leaves a driver process alive that nothing will ever close.
                        closeQuietly();
                        lifecycleMetrics.recordLaunchFailure(e.toString());
                        log.error("BROWSER_SESSION_MANAGER chromium launch FAILED after {}ms: {}",
                                System.currentTimeMillis() - launchStart, e.toString());
                        throw e;
                    }
                    long launchMs = System.currentTimeMillis() - launchStart;
                    lifecycleMetrics.recordLaunchSuccess(launchMs);
                    log.info("BROWSER_SESSION_MANAGER launched headless chromium with hardened options in {}ms", launchMs);
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

    /**
     * Restarts the shared browser+playwright process only if {@link #isZombie()}. Returns whether it
     * restarted.
     *
     * <p><b>Phase 12B — now refuses to restart while any lease is outstanding.</b> Previously this
     * closed the shared {@link Browser} unconditionally on a zombie verdict, which destroys the
     * {@link BrowserContext} of every <em>healthy</em> in-flight job as collateral: a single stuck
     * execution could abort a concurrent, perfectly fine submission mid-form. Worse, since the
     * zombie heuristic fires on {@code openContextCount >= 5}, the exact condition that triggers a
     * restart is "several contexts are currently open" — i.e. the case most likely to have live work
     * in it. Deferring is safe because the {@code BrowserLeasePool}'s own TTL reclaim returns those
     * leases shortly, after which the next check finds the pool idle and the restart proceeds.
     */
    public boolean restartIfZombie() {
        if (!isZombie()) return false;
        int active = activeLeases();
        if (active > 0) {
            log.warn("BROWSER_SESSION_MANAGER zombie detected but deferring restart — {} lease(s) still "
                    + "active; restarting now would destroy healthy in-flight sessions", active);
            return false;
        }
        synchronized (lock) {
            closeQuietly();
        }
        lifecycleMetrics.recordRestart();
        lifecycleMetrics.recordCrash("zombie detection: stuck or leaking browser contexts");
        log.warn("BROWSER_SESSION_MANAGER restarted due to zombie detection (stuck/leaking contexts)");
        return true;
    }

    /**
     * Outstanding pool leases, or {@code 0} when the pool is not resolvable. Never throws — this is
     * consulted on the restart path, where a lookup failure must not prevent recovery.
     */
    private int activeLeases() {
        try {
            ai.careerpilot.execution.browser.pool.BrowserLeasePool pool = leasePoolProvider.getIfAvailable();
            return pool == null ? 0 : pool.activeLeases();
        } catch (Exception e) {
            log.warn("BROWSER_SESSION_MANAGER lease-pool lookup failed during restart check: {}", e.toString());
            return 0;
        }
    }

    // ── Phase 12B — health accessors. Read-only; no side effects, and specifically no lazy launch:
    // calling any of these on a dark deployment must never start a browser. ──

    /** Whether Chromium is currently launched in this process. */
    public boolean isLaunched() {
        return browser != null;
    }

    /**
     * Chromium's reported version, or {@code null} if it has never been launched. Deliberately does
     * <b>not</b> shell out to {@code chromium --version} when unlaunched: this value is served by an
     * unauthenticated diagnostics endpoint, and spawning a process on request is not a trade worth
     * making for a string. "Not launched yet" is the honest answer.
     */
    public String browserVersion() {
        Browser b = browser;
        if (b == null) return null;
        try {
            return b.version();
        } catch (Exception e) {
            return null;
        }
    }

    /** Contexts currently open process-wide. Metadata only — see the zombie-detection note above. */
    public int openContexts() {
        return openContextCount.get();
    }

    public Instant lastContextOpenedAt() {
        return lastContextOpenedAt;
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            closeQuietly();
        }
    }

    /**
     * Tears down browser + playwright and resets counters. Caller must hold {@link #lock} (or be in
     * a construction-failure path where no other thread can observe the fields yet). Every close is
     * individually guarded so a failure in one never skips the other — a leaked Playwright driver
     * process outlives the container's usefulness.
     */
    private void closeQuietly() {
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
