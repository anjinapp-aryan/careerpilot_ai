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

    /** Idle window after which an unused browser is shut down. {@code <= 0} disables idle shutdown. */
    private final Duration idleTimeout;

    /** Contexts served before the browser is recycled. {@code <= 0} disables the count trigger. */
    private final long maxContextsPerBrowser;

    /** Uptime after which the browser is recycled. {@code <= 0} disables the uptime trigger. */
    private final Duration maxUptime;

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

    // ── P3 — recycle bookkeeping. All three are written under `lock` (or by contextClosed(), which
    // only moves the activity clock forward) and read under `lock` in recycleIfDue(). ──

    /** When the current browser was launched; {@code null} whenever no browser is running. */
    private volatile Instant launchedAt;

    /** Last time a context was opened OR closed — the clock the idle trigger measures against. */
    private volatile Instant lastActivityAt;

    /** Contexts served by the <em>current</em> browser. Reset to zero on every teardown. */
    private final java.util.concurrent.atomic.AtomicLong contextsSinceLaunch =
            new java.util.concurrent.atomic.AtomicLong();

    public BrowserSessionManager(@Value("${browser.automation.enabled:false}") boolean enabled,
                                 ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory launchOptions,
                                 BrowserLifecycleMetrics lifecycleMetrics,
                                 org.springframework.beans.factory.ObjectProvider<
                                         ai.careerpilot.execution.browser.pool.BrowserLeasePool> leasePoolProvider,
                                 @Value("${browser.automation.lifecycle.idle-timeout-seconds:300}") long idleTimeoutSeconds,
                                 @Value("${browser.automation.lifecycle.max-contexts:100}") long maxContextsPerBrowser,
                                 @Value("${browser.automation.lifecycle.max-uptime-seconds:21600}") long maxUptimeSeconds) {
        this.enabled = enabled;
        this.launchOptions = launchOptions;
        this.lifecycleMetrics = lifecycleMetrics;
        this.leasePoolProvider = leasePoolProvider;
        this.idleTimeout = Duration.ofSeconds(idleTimeoutSeconds);
        this.maxContextsPerBrowser = maxContextsPerBrowser;
        this.maxUptime = Duration.ofSeconds(maxUptimeSeconds);
    }

    /**
     * Why the browser was (or would be) recycled. {@code NOT_DUE} and the two {@code DEFERRED_*}
     * values are the "did nothing" outcomes; the three trigger values mean a teardown happened.
     */
    public enum RecycleOutcome {
        /** No browser is running — nothing to do, and specifically nothing to launch. */
        NOT_LAUNCHED,
        /** Running, but no trigger fired. */
        NOT_DUE,
        /** A pool permit is held: an execution is in flight or starting. Never tear down under one. */
        DEFERRED_LEASE_ACTIVE,
        /** A context is open. Same reasoning, checked independently of the pool. */
        DEFERRED_CONTEXT_OPEN,
        /** Idle longer than {@code lifecycle.idle-timeout-seconds}. */
        IDLE,
        /** Served {@code lifecycle.max-contexts} contexts. */
        MAX_CONTEXTS,
        /** Up longer than {@code lifecycle.max-uptime-seconds}. */
        MAX_UPTIME;

        /** True for the three outcomes that actually closed the browser. */
        public boolean recycled() {
            return this == IDLE || this == MAX_CONTEXTS || this == MAX_UPTIME;
        }
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
                        // Suppresses Playwright's first-run browser download when an external
                        // Chromium is configured — see BrowserLaunchOptionsFactory#driverEnv for
                        // the measured cost (83s and hundreds of MB, for browsers never launched).
                        java.util.Map<String, String> driverEnv = launchOptions.driverEnv();
                        playwright = driverEnv.isEmpty()
                                ? Playwright.create()
                                : Playwright.create(new Playwright.CreateOptions().setEnv(driverEnv));
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
                    launchedAt = Instant.now();
                    lastActivityAt = launchedAt;
                    contextsSinceLaunch.set(0);
                    lifecycleMetrics.recordLaunchSuccess(launchMs);
                    log.info("BROWSER_SESSION_MANAGER launched headless chromium with hardened options in {}ms", launchMs);
                }
            }
        }
        return b;
    }

    /**
     * A brand-new, isolated browser context — no shared cookies/storage with any other execution.
     *
     * <p><b>P3 — the whole body now runs under {@link #lock}.</b> {@link #recycleIfDue()} tears the
     * browser down under the same lock, so without this a recycle could land between {@link
     * #browser()} returning and {@code newContext()} being called on it, handing a live execution a
     * closed browser. Holding the lock also makes the relaunch-after-recycle path automatic: a
     * caller that blocks here simply finds {@code browser == null} and launches a fresh one.
     *
     * <p>Serialising context creation costs nothing in this deployment — one browser, one lease,
     * sequential execution — and it is the reason the recycle checks can be a simple read rather
     * than a compare-and-swap protocol.
     */
    public BrowserContext newContext() {
        synchronized (lock) {
            BrowserContext ctx = browser().newContext();
            openContextCount.incrementAndGet();
            contextsSinceLaunch.incrementAndGet();
            Instant now = Instant.now();
            lastContextOpenedAt = now;
            lastActivityAt = now;
            return ctx;
        }
    }

    /** Phase 7.16.3 — called by {@code PlaywrightAutomationProvider#logout} once its context is closed. */
    public void contextClosed() {
        openContextCount.updateAndGet(c -> Math.max(0, c - 1));
        // Starts the idle clock at the moment the browser genuinely became unused, not at the
        // moment its last context was opened — a 40-minute execution must not count as 40 minutes
        // of idleness the instant it finishes.
        lastActivityAt = Instant.now();
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
     * P3 — shuts the shared browser down when it is genuinely unused, and recycles it periodically.
     *
     * <p><b>Root cause this addresses.</b> The browser was launched lazily and then never closed
     * except at {@code @PreDestroy} or on a zombie verdict (which requires 5+ open contexts). After
     * a single validation, Chromium's six processes plus the Node driver stayed resident
     * indefinitely — measured at <b>+178 MiB of container memory, retained across 30s of complete
     * idleness</b>. On a sequential, occasional-use workload that is memory rented 24×7 for minutes
     * of use per day. The periodic recycle exists for a different reason: not leaks (none were
     * found) but the state a long-lived browser naturally accumulates — renderer state, V8 heap
     * fragmentation, network stack and internal caches.
     *
     * <p><b>Why this is the safe shape.</b> Teardown happens only under {@link #lock}, the same lock
     * {@link #newContext()} holds for its whole body, so a recycle can never interleave with an
     * execution obtaining a context. Three independent conditions must all say "unused" first:
     * <ol>
     *   <li><b>No pool permit is held.</b> Checked as {@code availablePermits == maxLeases}, not as
     *       {@code activeLeases == 0}: the pool takes its permit <em>before</em> calling
     *       {@code newContext()} and registers the lease <em>after</em>, so there is a window where
     *       a real execution is starting and {@code activeLeases()} still reads zero. The permit
     *       count has no such window.</li>
     *   <li><b>No lease is registered</b> — the same question asked the other way, kept because it
     *       costs nothing and covers a pool that is unresolvable or mid-shutdown.</li>
     *   <li><b>No context is open</b> — asked of this class's own counter, so a disagreement between
     *       the pool and the session manager resolves toward not tearing down.</li>
     * </ol>
     * Any of them saying otherwise returns a {@code DEFERRED_*} outcome and tries again next sweep.
     *
     * <p>Triggers are evaluated deterministically in a fixed order — contexts, then uptime, then
     * idle — so the reported reason for a teardown is reproducible rather than dependent on which
     * threshold happened to be read first. Recycling is only ever a teardown: the next execution
     * re-launches lazily through the existing path, which now costs ~1.1s.
     *
     * <p>Never throws, never launches. Returns what it did.
     */
    public RecycleOutcome recycleIfDue() {
        if (!enabled) return RecycleOutcome.NOT_LAUNCHED;
        if (browser == null) return RecycleOutcome.NOT_LAUNCHED;

        synchronized (lock) {
            // Re-read under the lock: another thread may have torn the browser down already.
            if (browser == null) return RecycleOutcome.NOT_LAUNCHED;

            if (permitHeld()) return RecycleOutcome.DEFERRED_LEASE_ACTIVE;
            if (activeLeases() > 0) return RecycleOutcome.DEFERRED_LEASE_ACTIVE;
            if (openContextCount.get() > 0) return RecycleOutcome.DEFERRED_CONTEXT_OPEN;

            RecycleOutcome reason = dueReason();
            if (!reason.recycled()) return reason;

            long served = contextsSinceLaunch.get();
            Duration uptime = launchedAt == null ? Duration.ZERO : Duration.between(launchedAt, Instant.now());
            closeQuietly();
            lifecycleMetrics.recordRestart();
            log.info("BROWSER_SESSION_MANAGER released chromium — reason={} contextsServed={} uptimeSeconds={}; "
                            + "the next execution relaunches lazily",
                    reason, served, uptime.toSeconds());
            return reason;
        }
    }

    /**
     * Which trigger, if any, is due. Fixed evaluation order so the reported reason is deterministic.
     * A non-positive configured value disables that trigger outright, which is the documented
     * rollback for each one independently.
     */
    private RecycleOutcome dueReason() {
        Instant now = Instant.now();
        if (maxContextsPerBrowser > 0 && contextsSinceLaunch.get() >= maxContextsPerBrowser) {
            return RecycleOutcome.MAX_CONTEXTS;
        }
        Instant started = launchedAt;
        if (!maxUptime.isZero() && !maxUptime.isNegative()
                && started != null && Duration.between(started, now).compareTo(maxUptime) >= 0) {
            return RecycleOutcome.MAX_UPTIME;
        }
        Instant last = lastActivityAt;
        if (!idleTimeout.isZero() && !idleTimeout.isNegative()
                && last != null && Duration.between(last, now).compareTo(idleTimeout) >= 0) {
            return RecycleOutcome.IDLE;
        }
        return RecycleOutcome.NOT_DUE;
    }

    /**
     * Whether any pool permit is currently checked out. Fails <em>closed</em> — an unresolvable or
     * throwing pool reports "in use", because wrongly believing the browser is free is the only
     * error here that can break a live execution.
     */
    private boolean permitHeld() {
        try {
            ai.careerpilot.execution.browser.pool.BrowserLeasePool pool = leasePoolProvider.getIfAvailable();
            if (pool == null) return false;   // no pool means nothing can be holding a permit
            return pool.availablePermits() < pool.maxLeases();
        } catch (Exception e) {
            log.warn("BROWSER_SESSION_MANAGER permit check failed, treating browser as in use: {}", e.toString());
            return true;
        }
    }

    /** Contexts served by the currently-running browser. Zero when none is running. */
    public long contextsSinceLaunch() {
        return contextsSinceLaunch.get();
    }

    /** When the current browser was launched, or {@code null} if none is running. */
    public Instant launchedAt() {
        return launchedAt;
    }

    /** Last context open/close, or {@code null} if no browser has run. */
    public Instant lastActivityAt() {
        return lastActivityAt;
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
        // Reset so the next launch starts its own lifetime rather than inheriting the previous
        // browser's context count and uptime — otherwise a recycled browser would be due for
        // recycling again immediately.
        contextsSinceLaunch.set(0);
        launchedAt = null;
    }
}
