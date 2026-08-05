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
                        .getBeanProvider(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class));
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
        return new BrowserSessionManager(false, launchOptions(), new BrowserLifecycleMetrics(),
                factory.getBeanProvider(ai.careerpilot.execution.browser.pool.BrowserLeasePool.class));
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
}
