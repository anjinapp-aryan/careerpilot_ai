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

    @Test
    void freshManagerIsNeverAZombie() {
        BrowserSessionManager mgr = new BrowserSessionManager(false);
        assertThat(mgr.isZombie()).isFalse();
    }

    @Test
    void restartIfZombieIsANoOpWhenNotAZombie() {
        BrowserSessionManager mgr = new BrowserSessionManager(false);
        assertThat(mgr.restartIfZombie()).isFalse();
    }

    @Test
    void contextClosedNeverGoesNegative() {
        BrowserSessionManager mgr = new BrowserSessionManager(false);
        // No contexts were ever opened (newContext() would throw with automation disabled) —
        // closing anyway must not underflow the counter into a permanently-zombie state.
        mgr.contextClosed();
        mgr.contextClosed();
        assertThat(mgr.isZombie()).isFalse();
    }
}
