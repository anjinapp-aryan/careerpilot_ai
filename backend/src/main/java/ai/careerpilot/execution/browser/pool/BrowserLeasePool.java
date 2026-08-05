package ai.careerpilot.execution.browser.pool;

import ai.careerpilot.execution.browser.BrowserSessionManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enterprise Browser Automation — <b>the memory bound for the entire platform.</b>
 *
 * <p>This class exists because the audit found the bound everyone assumed was in place was not.
 * {@code browser.automation.executor.max-pool-size=2} bounds a <em>thread pool</em>, but
 * {@code ApplicationExecutionService.execute()} — which opens a Chromium context — is reachable
 * from four independent pools: {@code applicationSubmissionExecutor} (max 4),
 * <b>Tomcat HTTP request threads</b> (via {@code ExecutionController}'s manual retry),
 * {@code RecoveryScheduler}, and {@code browserAutomationExecutor}. The previous
 * {@code BrowserSessionManager.newContext()} had no gate whatsoever, so concurrent Chromium
 * contexts were effectively unbounded. At roughly 250–600 MB per context+page, that is an
 * out-of-memory kill on a 3 GB VM the first time the feature flag is turned on.
 *
 * <p><b>Permits are pooled; contexts are not.</b> See {@link ContextLease} for why reusing a
 * context would be a cross-tenant data leak. The semaphore is what caps memory; the
 * fresh-context-per-lease rule is what keeps applicants isolated.
 *
 * <p><b>A forgotten lease cannot permanently consume capacity.</b> Every outstanding lease is in
 * a registry keyed by id; {@link #reclaimExpired()} destroys any that outlives its TTL and returns
 * its permit. Reclaim runs opportunistically on every acquire — exactly when capacity matters —
 * so no extra scheduler thread is introduced. {@code ConcurrentHashMap#remove} is the atomic
 * arbiter between a reclaiming sweep and a late {@link ContextLease#close()}, so a permit is
 * released exactly once no matter which wins.
 *
 * <p>Defaults are sized for the documented deployment target, not for a developer laptop:
 * <b>one</b> concurrent lease. Raising it is a deliberate act that must follow a real RAM
 * measurement (see {@code browser.automation.pool.max-leases}).
 */
@Component
public class BrowserLeasePool {

    private static final Logger log = LoggerFactory.getLogger(BrowserLeasePool.class);

    private final BrowserSessionManager sessionManager;
    private final BrowserPoolMetrics metrics;

    private final int maxLeases;
    private final Duration leaseTtl;
    private final Duration acquireTimeout;
    private final Duration pageTimeout;
    private final Duration drainTimeout;

    private final Semaphore permits;
    private final Map<UUID, ContextLease> outstanding = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public BrowserLeasePool(BrowserSessionManager sessionManager,
                            BrowserPoolMetrics metrics,
                            @Value("${browser.automation.pool.max-leases:1}") int maxLeases,
                            @Value("${browser.automation.pool.lease-ttl-seconds:180}") long leaseTtlSeconds,
                            @Value("${browser.automation.pool.acquire-timeout-seconds:30}") long acquireTimeoutSeconds,
                            @Value("${browser.automation.pool.page-timeout-seconds:30}") long pageTimeoutSeconds,
                            @Value("${browser.automation.pool.drain-timeout-seconds:20}") long drainTimeoutSeconds) {
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        this.maxLeases = Math.max(1, maxLeases);
        // Floor of 1s rather than something larger: the floor exists only to reject nonsense
        // config (0/negative), and a higher floor would make the reclaim path untestable without a
        // real multi-second sleep in the suite. Production sets 180 via configuration.
        this.leaseTtl = Duration.ofSeconds(Math.max(1, leaseTtlSeconds));
        this.acquireTimeout = Duration.ofSeconds(Math.max(1, acquireTimeoutSeconds));
        this.pageTimeout = Duration.ofSeconds(Math.max(5, pageTimeoutSeconds));
        this.drainTimeout = Duration.ofMillis(Math.max(0, drainTimeoutSeconds * 1000));
        // Fair ordering: a job that has been waiting must not be starved by a newly-arriving one.
        this.permits = new Semaphore(this.maxLeases, true);
        log.info("BROWSER_POOL initialised maxLeases={} leaseTtl={}s acquireTimeout={}s",
                this.maxLeases, this.leaseTtl.toSeconds(), this.acquireTimeout.toSeconds());
    }

    public int maxLeases() { return maxLeases; }
    public int activeLeases() { return outstanding.size(); }
    public int availablePermits() { return permits.availablePermits(); }
    public Duration leaseTtl() { return leaseTtl; }

    /**
     * Acquires exclusive browser capacity, blocking up to the configured acquire timeout.
     *
     * @throws BrowserCapacityUnavailableException when capacity does not free up in time. This is a
     *         deliberate, typed, recoverable signal rather than an unbounded wait: a caller that
     *         cannot get a browser should fail fast into the existing retry policy, not pile up.
     */
    public ContextLease acquire() {
        if (shuttingDown.get()) {
            throw new BrowserCapacityUnavailableException("browser pool is shutting down");
        }
        // Opportunistic reclaim: recover capacity abandoned by a caller that never released.
        reclaimExpired();

        long waitStart = System.currentTimeMillis();
        boolean got;
        try {
            got = permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserCapacityUnavailableException("interrupted while waiting for browser capacity");
        }
        long waited = System.currentTimeMillis() - waitStart;
        metrics.recordAcquireWait(waited);

        if (!got) {
            metrics.recordAcquireTimeout();
            throw new BrowserCapacityUnavailableException(
                    "no browser capacity within " + acquireTimeout.toSeconds() + "s (maxLeases="
                            + maxLeases + ", active=" + outstanding.size() + ")");
        }

        // From here the permit is held; any failure MUST return it or capacity leaks permanently.
        BrowserContext context = null;
        try {
            context = sessionManager.newContext();
            Page page = context.newPage();
            page.setDefaultTimeout(pageTimeout.toMillis());

            ContextLease lease = new ContextLease(context, page, this);
            outstanding.put(lease.id(), lease);
            metrics.recordAcquired(outstanding.size());
            log.debug("BROWSER_POOL leased {} active={}/{} waitedMs={}",
                    lease.id(), outstanding.size(), maxLeases, waited);
            return lease;
        } catch (RuntimeException e) {
            safeClose(context);
            permits.release();
            metrics.recordAcquireFailure();
            throw e;
        }
    }

    /** Called by {@link ContextLease#close()}. Idempotent via the registry's atomic remove. */
    void release(ContextLease lease) {
        if (lease == null) return;
        if (outstanding.remove(lease.id()) == null) {
            // Already reclaimed by the expiry sweep — its permit was returned there. Do not
            // double-release, or the pool would slowly grant more capacity than it owns.
            return;
        }
        long ageMs = lease.age().toMillis();
        destroyAndReturnPermit(lease);
        metrics.recordReleased(ageMs, outstanding.size());
        log.debug("BROWSER_POOL released {} ageMs={} active={}/{}",
                lease.id(), ageMs, outstanding.size(), maxLeases);
    }

    /**
     * The single teardown path. Destroying the context and returning the permit must always happen
     * together, and {@code sessionManager.contextClosed()} must be told too — otherwise its
     * open-context counter drifts upward and permanently false-positives zombie detection, which is
     * exactly the class of bug the old ThreadLocal path suffered from.
     */
    private void destroyAndReturnPermit(ContextLease lease) {
        try {
            lease.destroy(log);
        } finally {
            try {
                sessionManager.contextClosed();
            } catch (Exception e) {
                log.warn("BROWSER_POOL contextClosed notify failed lease={}: {}", lease.id(), e.toString());
            }
            permits.release();
        }
    }

    /**
     * Destroys every lease older than the TTL and returns its permit. This is what makes a
     * forgotten or hung lease survivable: previously a stuck job held a browser context
     * indefinitely and the only remedy was restarting the entire shared browser, which also killed
     * every healthy in-flight job.
     *
     * @return how many leases were reclaimed
     */
    public int reclaimExpired() {
        if (outstanding.isEmpty()) return 0;
        int reclaimed = 0;
        for (ContextLease lease : new ArrayList<>(outstanding.values())) {
            if (lease.age().compareTo(leaseTtl) <= 0) continue;
            if (outstanding.remove(lease.id()) == null) continue; // lost the race to close(); fine
            lease.markReleased();
            destroyAndReturnPermit(lease);
            reclaimed++;
            metrics.recordExpired();
            log.warn("BROWSER_POOL reclaimed expired lease {} ageMs={} ttlMs={} ownerThread={}",
                    lease.id(), lease.age().toMillis(), leaseTtl.toMillis(), lease.ownerThread());
        }
        return reclaimed;
    }

    /** True when every permit is outstanding — used by health reporting, not by control flow. */
    public boolean isSaturated() {
        return permits.availablePermits() == 0;
    }

    /**
     * Graceful shutdown: stop granting new leases, then wait briefly for in-flight ones to finish
     * before force-destroying the remainder. Previously {@code @PreDestroy} closed the browser
     * outright while jobs were mid-submission.
     */
    @PreDestroy
    public void drainAndShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        long deadline = System.currentTimeMillis() + drainTimeout.toMillis();
        while (!outstanding.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!outstanding.isEmpty()) {
            log.warn("BROWSER_POOL shutdown with {} lease(s) still active — force-destroying", outstanding.size());
            for (ContextLease lease : new ArrayList<>(outstanding.values())) {
                if (outstanding.remove(lease.id()) == null) continue;
                lease.markReleased();
                destroyAndReturnPermit(lease);
            }
        }
        log.info("BROWSER_POOL drained");
    }

    /** Live pool state for {@code ExecutionDiagnosticsController}. Metadata only — no page content. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxLeases", maxLeases);
        out.put("activeLeases", outstanding.size());
        out.put("availablePermits", permits.availablePermits());
        out.put("saturated", isSaturated());
        out.put("leaseTtlSeconds", leaseTtl.toSeconds());
        out.put("acquireTimeoutSeconds", acquireTimeout.toSeconds());
        out.put("queuedWaiters", permits.getQueueLength());
        out.put("shuttingDown", shuttingDown.get());
        List<Map<String, Object>> active = new ArrayList<>();
        for (ContextLease lease : outstanding.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("leaseId", lease.id().toString());
            entry.put("ageMs", lease.age().toMillis());
            entry.put("ownerThread", lease.ownerThread());
            active.add(entry);
        }
        out.put("active", active);
        return out;
    }

    private void safeClose(BrowserContext context) {
        try {
            if (context != null) context.close();
        } catch (Exception e) {
            log.warn("BROWSER_POOL context close during failed acquire: {}", e.toString());
        }
    }

    /** Typed, recoverable "no capacity" signal — maps onto the existing retry policy, never a crash. */
    public static class BrowserCapacityUnavailableException extends RuntimeException {
        public BrowserCapacityUnavailableException(String message) {
            super(message);
        }
    }
}
