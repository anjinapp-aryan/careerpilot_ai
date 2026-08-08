package ai.careerpilot.execution.browser;

import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 12B — closes a real recovery gap: <b>an idle system never recovered.</b>
 *
 * <p>{@code BrowserLeasePool#reclaimExpired()} runs opportunistically inside {@code acquire()},
 * which is the right place for it while traffic is flowing but means a leaked lease is only ever
 * reclaimed by the <em>next</em> job. With {@code max-leases=1} — the production default — one
 * leaked lease consumes the entire budget, so there is no next job: every subsequent acquire waits
 * out its timeout and fails. The pool deadlocks itself, and the only remedy is a restart.
 *
 * <p>Likewise {@code BrowserSessionManager#restartIfZombie()} is reached only from
 * {@code AutomationRecoveryService}, i.e. only when an execution has already failed and been
 * classified {@code BROWSER_FAILURE}. A browser that wedges without any execution failing to
 * classify — precisely the wedge that stops executions from starting — is never noticed.
 *
 * <p>This scheduler is the periodic sweep that makes both paths self-healing. It deliberately does
 * <b>not</b> introduce recovery logic of its own: it calls the two existing methods on a timer and
 * nothing else. {@code @EnableScheduling} is already active on {@code CareerPilotApplication} (see
 * {@code RecoveryScheduler}, {@code JobDiscoveryScheduler}).
 *
 * <p>Gated by {@code browser.automation.maintenance.enabled} (default {@code false}) <em>and</em> by
 * the master {@code browser.automation.enabled}. Both off means this method returns before touching
 * any bean, so a dark deployment is byte-identical to pre-Phase-12B.
 */
@Component
public class BrowserMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(BrowserMaintenanceScheduler.class);

    private final BrowserLeasePool leasePool;
    private final BrowserSessionManager sessionManager;
    private final boolean maintenanceEnabled;
    private final boolean automationEnabled;
    private final boolean zombieRestartEnabled;
    private final boolean lifecycleEnabled;

    public BrowserMaintenanceScheduler(
            BrowserLeasePool leasePool,
            BrowserSessionManager sessionManager,
            @Value("${browser.automation.maintenance.enabled:false}") boolean maintenanceEnabled,
            @Value("${browser.automation.enabled:false}") boolean automationEnabled,
            @Value("${browser.automation.maintenance.zombie-restart-enabled:true}") boolean zombieRestartEnabled,
            @Value("${browser.automation.lifecycle.enabled:true}") boolean lifecycleEnabled) {
        this.leasePool = leasePool;
        this.sessionManager = sessionManager;
        this.maintenanceEnabled = maintenanceEnabled;
        this.automationEnabled = automationEnabled;
        this.zombieRestartEnabled = zombieRestartEnabled;
        this.lifecycleEnabled = lifecycleEnabled;
    }

    /**
     * Never throws. A maintenance sweep that propagates would be logged by Spring's scheduler and
     * then silently keep firing; worse, a failure in the reclaim half would skip the zombie half.
     * Each half is isolated so one broken subsystem cannot disable the other's recovery.
     */
    @Scheduled(fixedDelayString = "${browser.automation.maintenance.interval-ms:60000}")
    public void sweep() {
        if (!maintenanceEnabled || !automationEnabled) return;

        try {
            int reclaimed = leasePool.reclaimExpired();
            if (reclaimed > 0) {
                // Deliberately WARN, not INFO: reclaiming means some code path failed to release.
                // The pool recovered, but the leak is still a defect worth surfacing.
                log.warn("BROWSER_MAINTENANCE reclaimed {} expired lease(s) — a caller is not releasing", reclaimed);
            }
        } catch (Exception e) {
            log.warn("BROWSER_MAINTENANCE lease reclaim failed: {}", e.toString());
        }

        if (zombieRestartEnabled) {
            try {
                // restartIfZombie() is itself a no-op unless the browser genuinely looks wedged AND no
                // lease is outstanding (see its javadoc) — this scheduler adds a trigger, not a policy.
                if (sessionManager.restartIfZombie()) {
                    log.warn("BROWSER_MAINTENANCE restarted a wedged browser during periodic sweep");
                }
            } catch (Exception e) {
                log.warn("BROWSER_MAINTENANCE zombie check failed: {}", e.toString());
            }
        }

        // P3 — idle shutdown and periodic recycle. Third independently-isolated half, for the same
        // reason as the other two: a failure here must not disable lease reclaim or zombie recovery.
        // Like them, this contributes a trigger and no policy — every safety decision (permit held,
        // lease registered, context open, which threshold is due) lives in recycleIfDue().
        if (!lifecycleEnabled) return;
        try {
            BrowserSessionManager.RecycleOutcome outcome = sessionManager.recycleIfDue();
            if (outcome.recycled()) {
                log.info("BROWSER_MAINTENANCE released the idle browser during periodic sweep (reason={})", outcome);
            }
        } catch (Exception e) {
            log.warn("BROWSER_MAINTENANCE lifecycle check failed: {}", e.toString());
        }
    }
}
