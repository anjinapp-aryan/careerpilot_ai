package ai.careerpilot.execution.browser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7.16.3 — zombie detection is a coarse, in-memory open-context counter (no real Playwright
 * process is launched here since {@code browser.automation.enabled=false}; {@link
 * BrowserSessionManager#newContext()} would throw before ever incrementing the counter under real
 * Playwright launch — these tests exercise the counter/threshold logic directly instead).
 */
class BrowserSessionManagerTest {

    private static final long IDLE_TIMEOUT_SECONDS = 300;
    private static final long MAX_CONTEXTS = 100;
    private static final long MAX_UPTIME_SECONDS = 21600;

    /** Real factory (pure config, launches nothing) — these tests exercise counter logic only. */
    private static ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory launchOptions() {
        return new ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory(
                true, true, 256, "", "", 60000);
    }

    /**
     * Phase 12B — the manager now consults the lease pool before restarting, so a zombie verdict
     * can no longer destroy healthy in-flight sessions. These tests construct no pool, so the
     * provider resolves to nothing and the manager treats that as "no active leases" — the intended
     * degradation, and the reason a real pool is not needed here.
     */
    private static BrowserSessionManager manager() {
        return new BrowserSessionManager(false, launchOptions(), new BrowserLifecycleMetrics(),
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class),
                IDLE_TIMEOUT_SECONDS, MAX_CONTEXTS, MAX_UPTIME_SECONDS);
    }

    @Test
    void freshManagerIsNeverAZombie() {
        BrowserSessionManager mgr = manager();
        assertThat(mgr.isZombie()).isFalse();
    }

    @Test
    void restartIfZombieIsANoOpWhenNotAZombie() {
        BrowserSessionManager mgr = manager();
        assertThat(mgr.restartIfZombie()).isFalse();
    }

    /**
     * Phase 12B — the restart guard. A zombie verdict must NOT tear down the shared browser while
     * leases are outstanding: doing so destroys the context of every healthy in-flight job as
     * collateral. This is not theoretical — the zombie heuristic fires on "5+ contexts open", i.e.
     * exactly the state most likely to contain live work.
     *
     * <p>Reaching a zombie state needs the private open-context counter, which cannot be driven
     * from outside without launching a real browser ({@code newContext()} throws while the flag is
     * off). Reflection is used deliberately rather than widening production visibility for a test.
     */
    @Test
    void aZombieWithOutstandingLeasesDefersRestartInsteadOfKillingHealthySessions() throws Exception {
        ai.careerpilot.execution.browser.pool.BrowserLeasePool pool =
                org.mockito.Mockito.mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        org.mockito.Mockito.when(pool.activeLeases()).thenReturn(1);

        BrowserSessionManager mgr = managerWithPool(pool);
        forceZombie(mgr);

        assertThat(mgr.isZombie()).isTrue();
        assertThat(mgr.restartIfZombie())
                .as("must defer while a lease is live — the pool's TTL reclaim frees it shortly")
                .isFalse();
    }

    @Test
    void aZombieWithNoOutstandingLeasesIsRestarted() {
        ai.careerpilot.execution.browser.pool.BrowserLeasePool pool =
                org.mockito.Mockito.mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        org.mockito.Mockito.when(pool.activeLeases()).thenReturn(0);

        BrowserSessionManager mgr = managerWithPool(pool);
        forceZombie(mgr);

        assertThat(mgr.restartIfZombie()).isTrue();
        // The restart resets the counter, so the manager is no longer a zombie afterwards.
        assertThat(mgr.isZombie()).isFalse();
    }

    @Test
    void healthAccessorsNeverLaunchABrowser() {
        BrowserSessionManager mgr = manager();
        assertThat(mgr.isLaunched()).isFalse();
        assertThat(mgr.browserVersion()).isNull();
        assertThat(mgr.openContexts()).isZero();
        assertThat(mgr.lastContextOpenedAt()).isNull();
    }

    private static BrowserSessionManager managerWithPool(
            ai.careerpilot.execution.browser.pool.BrowserLeasePool pool) {
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("browserLeasePool", pool);
        return managerWithPool(pool, IDLE_TIMEOUT_SECONDS, MAX_CONTEXTS, MAX_UPTIME_SECONDS);
    }

    private static BrowserSessionManager managerWithPool(
            ai.careerpilot.execution.browser.pool.BrowserLeasePool pool,
            long idleSeconds, long maxContexts, long maxUptimeSeconds) {
        org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("browserLeasePool", pool);
        return new BrowserSessionManager(true, launchOptions(), new BrowserLifecycleMetrics(),
                factory.getBeanProvider(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class),
                idleSeconds, maxContexts, maxUptimeSeconds);
    }

    private static void forceZombie(BrowserSessionManager mgr) {
        try {
            java.lang.reflect.Field field = BrowserSessionManager.class.getDeclaredField("openContextCount");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicInteger) field.get(mgr)).set(9);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("open-context counter field moved — update this test", e);
        }
    }

    @Test
    void contextClosedNeverGoesNegative() {
        BrowserSessionManager mgr = manager();
        // No contexts were ever opened (newContext() would throw with automation disabled) —
        // closing anyway must not underflow the counter into a permanently-zombie state.
        mgr.contextClosed();
        mgr.contextClosed();
        assertThat(mgr.isZombie()).isFalse();
    }

    // ── P3 — idle shutdown and periodic recycle ───────────────────────────────────────────────
    //
    // Measured motivation: after one validation, Chromium's six processes plus the Node driver
    // stayed resident indefinitely — +178 MiB of container memory retained across full idleness.
    // These tests pin the safety guards, which matter more than the saving: the browser must never
    // be torn down while an execution holds capacity.

    /** A pool that reports itself completely free — no permit held, no lease registered. */
    private static ai.careerpilot.execution.browser.pool.BrowserLeasePool idlePool() {
        var pool = org.mockito.Mockito.mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        org.mockito.Mockito.when(pool.activeLeases()).thenReturn(0);
        org.mockito.Mockito.when(pool.availablePermits()).thenReturn(1);
        org.mockito.Mockito.when(pool.maxLeases()).thenReturn(1);
        return pool;
    }

    /**
     * Puts the manager into the "browser is running" state without launching Chromium. Mirrors the
     * reflection already used by {@link #forceZombie} — same trade, same loud failure if a field is
     * renamed.
     */
    private static void simulateRunningBrowser(BrowserSessionManager mgr, long contextsServed,
                                               java.time.Instant launchedAt,
                                               java.time.Instant lastActivityAt) {
        try {
            set(mgr, "browser", org.mockito.Mockito.mock(com.microsoft.playwright.Browser.class));
            set(mgr, "playwright", org.mockito.Mockito.mock(com.microsoft.playwright.Playwright.class));
            set(mgr, "launchedAt", launchedAt);
            set(mgr, "lastActivityAt", lastActivityAt);
            java.lang.reflect.Field counter = BrowserSessionManager.class.getDeclaredField("contextsSinceLaunch");
            counter.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) counter.get(mgr)).set(contextsServed);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("lifecycle field moved — update this test", e);
        }
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field f = BrowserSessionManager.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void recycleIsANoOpWhenNoBrowserIsRunning() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        assertThat(mgr.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.NOT_LAUNCHED);
        assertThat(mgr.isLaunched()).isFalse();
    }

    @Test
    void recycleIsANoOpWhileTheBrowserIsStillWithinEveryThreshold() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant now = java.time.Instant.now();
        simulateRunningBrowser(mgr, 3, now, now);

        assertThat(mgr.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.NOT_DUE);
        assertThat(mgr.isLaunched()).as("a healthy browser must survive the sweep").isTrue();
    }

    @Test
    void anIdleBrowserIsReleased() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant longAgo = java.time.Instant.now().minusSeconds(IDLE_TIMEOUT_SECONDS + 5);
        simulateRunningBrowser(mgr, 1, longAgo, longAgo);

        assertThat(mgr.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.IDLE);
        assertThat(mgr.isLaunched()).isFalse();
    }

    /**
     * THE critical guard. The pool takes its permit BEFORE calling {@code newContext()} and
     * registers the lease AFTER, so there is a window in which a real execution is starting and
     * {@code activeLeases()} still reads zero. Checking permits closes that window; checking only
     * leases would hand a live execution a closed browser.
     */
    @Test
    void anIdleBrowserIsNotReleasedWhileAPoolPermitIsHeld() {
        var pool = org.mockito.Mockito.mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        org.mockito.Mockito.when(pool.activeLeases()).thenReturn(0);       // lease not registered yet
        org.mockito.Mockito.when(pool.availablePermits()).thenReturn(0);   // but capacity IS taken
        org.mockito.Mockito.when(pool.maxLeases()).thenReturn(1);

        BrowserSessionManager mgr = managerWithPool(pool);
        java.time.Instant longAgo = java.time.Instant.now().minusSeconds(IDLE_TIMEOUT_SECONDS + 5);
        simulateRunningBrowser(mgr, 1, longAgo, longAgo);

        assertThat(mgr.recycleIfDue())
                .isEqualTo(BrowserSessionManager.RecycleOutcome.DEFERRED_LEASE_ACTIVE);
        assertThat(mgr.isLaunched()).isTrue();
    }

    @Test
    void anIdleBrowserIsNotReleasedWhileALeaseIsRegistered() {
        var pool = org.mockito.Mockito.mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        org.mockito.Mockito.when(pool.activeLeases()).thenReturn(1);
        org.mockito.Mockito.when(pool.availablePermits()).thenReturn(1);
        org.mockito.Mockito.when(pool.maxLeases()).thenReturn(1);

        BrowserSessionManager mgr = managerWithPool(pool);
        java.time.Instant longAgo = java.time.Instant.now().minusSeconds(IDLE_TIMEOUT_SECONDS + 5);
        simulateRunningBrowser(mgr, 1, longAgo, longAgo);

        assertThat(mgr.recycleIfDue())
                .isEqualTo(BrowserSessionManager.RecycleOutcome.DEFERRED_LEASE_ACTIVE);
        assertThat(mgr.isLaunched()).isTrue();
    }

    @Test
    void anIdleBrowserIsNotReleasedWhileAContextIsOpen() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant longAgo = java.time.Instant.now().minusSeconds(IDLE_TIMEOUT_SECONDS + 5);
        simulateRunningBrowser(mgr, 1, longAgo, longAgo);
        forceZombie(mgr);   // sets the open-context counter to a non-zero value

        assertThat(mgr.recycleIfDue())
                .isEqualTo(BrowserSessionManager.RecycleOutcome.DEFERRED_CONTEXT_OPEN);
        assertThat(mgr.isLaunched()).isTrue();
    }

    @Test
    void anUnreadablePoolFailsClosedAndKeepsTheBrowser() {
        var pool = org.mockito.Mockito.mock(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class);
        org.mockito.Mockito.when(pool.availablePermits()).thenThrow(new IllegalStateException("pool broken"));

        BrowserSessionManager mgr = managerWithPool(pool);
        java.time.Instant longAgo = java.time.Instant.now().minusSeconds(IDLE_TIMEOUT_SECONDS + 5);
        simulateRunningBrowser(mgr, 1, longAgo, longAgo);

        // Wrongly believing the browser is free is the only error here that can break a live
        // execution, so an unreadable pool must mean "in use".
        assertThat(mgr.recycleIfDue())
                .isEqualTo(BrowserSessionManager.RecycleOutcome.DEFERRED_LEASE_ACTIVE);
        assertThat(mgr.isLaunched()).isTrue();
    }

    @Test
    void aBrowserThatServedItsContextBudgetIsRecycled() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant now = java.time.Instant.now();
        simulateRunningBrowser(mgr, MAX_CONTEXTS, now, now);   // not idle, not old — only the count

        assertThat(mgr.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.MAX_CONTEXTS);
        assertThat(mgr.isLaunched()).isFalse();
    }

    @Test
    void aBrowserPastItsUptimeBudgetIsRecycled() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant now = java.time.Instant.now();
        simulateRunningBrowser(mgr, 1, now.minusSeconds(MAX_UPTIME_SECONDS + 1), now);   // busy, but old

        assertThat(mgr.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.MAX_UPTIME);
        assertThat(mgr.isLaunched()).isFalse();
    }

    @Test
    void recyclingResetsTheLifetimeSoTheNextBrowserIsNotImmediatelyDueAgain() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant now = java.time.Instant.now();
        simulateRunningBrowser(mgr, MAX_CONTEXTS, now.minusSeconds(MAX_UPTIME_SECONDS + 1), now);

        assertThat(mgr.recycleIfDue().recycled()).isTrue();
        assertThat(mgr.contextsSinceLaunch()).isZero();
        assertThat(mgr.launchedAt()).isNull();
        assertThat(mgr.openContexts()).isZero();
    }

    @Test
    void eachTriggerCanBeDisabledIndependently() {
        java.time.Instant old = java.time.Instant.now().minusSeconds(100_000);

        // Every trigger disabled (0) => nothing is ever due, however old or busy the browser is.
        BrowserSessionManager allOff = managerWithPool(idlePool(), 0, 0, 0);
        simulateRunningBrowser(allOff, 10_000, old, old);
        assertThat(allOff.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.NOT_DUE);
        assertThat(allOff.isLaunched()).isTrue();

        // Only the count trigger active.
        BrowserSessionManager countOnly = managerWithPool(idlePool(), 0, 5, 0);
        simulateRunningBrowser(countOnly, 5, old, old);
        assertThat(countOnly.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.MAX_CONTEXTS);

        // Only the idle trigger active.
        BrowserSessionManager idleOnly = managerWithPool(idlePool(), 30, 0, 0);
        simulateRunningBrowser(idleOnly, 10_000, old, old);
        assertThat(idleOnly.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.IDLE);
    }

    @Test
    void triggerOrderIsDeterministicWhenSeveralAreDueAtOnce() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant old = java.time.Instant.now().minusSeconds(100_000);
        simulateRunningBrowser(mgr, MAX_CONTEXTS + 50, old, old);   // all three thresholds exceeded

        assertThat(mgr.recycleIfDue())
                .as("contexts is evaluated first, so the reported reason is reproducible")
                .isEqualTo(BrowserSessionManager.RecycleOutcome.MAX_CONTEXTS);
    }

    @Test
    void recycleNeverLaunchesABrowserOnADarkDeployment() {
        BrowserSessionManager mgr = manager();   // automation disabled
        assertThat(mgr.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.NOT_LAUNCHED);
        assertThat(mgr.isLaunched()).isFalse();
    }

    @Test
    void lifecycleAccessorsAreNullOrZeroBeforeAnyLaunch() {
        BrowserSessionManager mgr = manager();
        assertThat(mgr.launchedAt()).isNull();
        assertThat(mgr.lastActivityAt()).isNull();
        assertThat(mgr.contextsSinceLaunch()).isZero();
    }

    @Test
    void contextClosedAdvancesTheIdleClock() {
        BrowserSessionManager mgr = managerWithPool(idlePool());
        java.time.Instant longAgo = java.time.Instant.now().minusSeconds(IDLE_TIMEOUT_SECONDS + 5);
        simulateRunningBrowser(mgr, 1, longAgo, longAgo);

        // A finishing execution must reset idleness, not leave the browser instantly collectable.
        mgr.contextClosed();

        assertThat(mgr.recycleIfDue()).isEqualTo(BrowserSessionManager.RecycleOutcome.NOT_DUE);
        assertThat(mgr.isLaunched()).isTrue();
    }
}
