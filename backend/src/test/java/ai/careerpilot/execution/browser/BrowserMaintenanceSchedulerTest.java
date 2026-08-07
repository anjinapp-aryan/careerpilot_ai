package ai.careerpilot.execution.browser;

import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 12B — the periodic sweep that makes lease reclaim and zombie restart reachable on an
 * <em>idle</em> system. Before this, {@code reclaimExpired()} only ran inside {@code acquire()},
 * which with the production default of one lease means a single leaked lease deadlocks the pool
 * permanently: there is no next acquire to trigger the reclaim.
 */
class BrowserMaintenanceSchedulerTest {

    private final BrowserLeasePool pool = mock(BrowserLeasePool.class);
    private final BrowserSessionManager sessionManager = mock(BrowserSessionManager.class);

    private BrowserMaintenanceScheduler scheduler(boolean maintenance, boolean automation, boolean zombieRestart) {
        return new BrowserMaintenanceScheduler(pool, sessionManager, maintenance, automation, zombieRestart, true);
    }

    @Test
    void disabledSweepTouchesNothing() {
        scheduler(false, true, true).sweep();
        verifyNoInteractions(pool, sessionManager);
    }

    @Test
    void masterFlagOffTouchesNothingEvenWhenMaintenanceIsEnabled() {
        // Both gates must agree — a dark deployment stays byte-identical to pre-Phase-12B.
        scheduler(true, false, true).sweep();
        verifyNoInteractions(pool, sessionManager);
    }

    @Test
    void enabledSweepReclaimsLeasesAndChecksForAWedgedBrowser() {
        when(pool.reclaimExpired()).thenReturn(2);
        when(sessionManager.restartIfZombie()).thenReturn(true);

        scheduler(true, true, true).sweep();

        verify(pool).reclaimExpired();
        verify(sessionManager).restartIfZombie();
    }

    @Test
    void zombieRestartCanBeDisabledIndependentlyOfReclaim() {
        scheduler(true, true, false).sweep();
        verify(pool).reclaimExpired();
        verify(sessionManager, never()).restartIfZombie();
    }

    @Test
    void aFailingReclaimNeverPreventsTheZombieCheck() {
        // The two halves are isolated on purpose: one broken subsystem must not disable the
        // other's recovery, which is exactly what a single shared try block would do.
        when(pool.reclaimExpired()).thenThrow(new IllegalStateException("pool exploded"));

        scheduler(true, true, true).sweep();

        verify(sessionManager).restartIfZombie();
    }

    @Test
    void aFailingZombieCheckNeverPropagates() {
        when(sessionManager.restartIfZombie()).thenThrow(new IllegalStateException("boom"));
        // A scheduled method that throws is logged and silently retried forever — never useful.
        scheduler(true, true, true).sweep();
    }

    // ── P3 — the third, independently-isolated half of the sweep ──────────────────────────────

    private BrowserMaintenanceScheduler scheduler(boolean maintenance, boolean automation,
                                                  boolean zombieRestart, boolean lifecycle) {
        return new BrowserMaintenanceScheduler(pool, sessionManager, maintenance, automation,
                zombieRestart, lifecycle);
    }

    @Test
    void sweepAsksTheSessionManagerToRecycleWhenLifecycleIsEnabled() {
        when(sessionManager.recycleIfDue()).thenReturn(BrowserSessionManager.RecycleOutcome.IDLE);
        scheduler(true, true, true, true).sweep();
        verify(sessionManager).recycleIfDue();
    }

    @Test
    void lifecycleCanBeTurnedOffWithoutDisablingReclaimOrZombieRecovery() {
        scheduler(true, true, true, false).sweep();
        verify(pool).reclaimExpired();
        verify(sessionManager).restartIfZombie();
        verify(sessionManager, never()).recycleIfDue();
    }

    @Test
    void aThrowingRecycleNeverEscapesTheSweep() {
        when(sessionManager.recycleIfDue()).thenThrow(new IllegalStateException("boom"));
        scheduler(true, true, true, true).sweep();   // must not throw
        verify(sessionManager).recycleIfDue();
    }

    @Test
    void aThrowingZombieCheckStillLetsTheRecycleRun() {
        // Each half is isolated so one broken subsystem cannot disable another's recovery.
        when(sessionManager.restartIfZombie()).thenThrow(new IllegalStateException("boom"));
        when(sessionManager.recycleIfDue()).thenReturn(BrowserSessionManager.RecycleOutcome.NOT_DUE);
        scheduler(true, true, true, true).sweep();
        verify(sessionManager).recycleIfDue();
    }

    @Test
    void aDarkDeploymentNeverReachesTheRecycleCheck() {
        scheduler(true, false, true, true).sweep();
        verifyNoInteractions(sessionManager);
    }
}
