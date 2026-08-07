package ai.careerpilot.execution.browser.pool;

import ai.careerpilot.execution.browser.BrowserSessionManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Enterprise Browser Automation — the pool is the platform's memory bound, so these tests assert
 * the guarantees that bound actually depends on: capacity is never exceeded, a permit is returned
 * exactly once on every path, and an abandoned lease cannot permanently consume capacity.
 */
class BrowserLeasePoolTest {

    private BrowserSessionManager sessionManager;
    private BrowserPoolMetrics metrics;

    @BeforeEach
    void setUp() {
        sessionManager = mock(BrowserSessionManager.class);
        metrics = new BrowserPoolMetrics();
        when(sessionManager.newContext()).thenAnswer(inv -> {
            BrowserContext ctx = mock(BrowserContext.class);
            Page page = mock(Page.class);
            when(ctx.newPage()).thenReturn(page);
            return ctx;
        });
    }

    /** Drain timeout is 0 in tests so the shutdown case does not stall the suite for 20s. */
    private BrowserLeasePool pool(int maxLeases, long ttlSeconds, long acquireTimeoutSeconds) {
        return new BrowserLeasePool(sessionManager, metrics, maxLeases, ttlSeconds, acquireTimeoutSeconds, 30, 0);
    }

    @Test
    void acquireProducesAUsableLeaseAndTracksIt() {
        BrowserLeasePool pool = pool(1, 180, 5);
        try (ContextLease lease = pool.acquire()) {
            assertThat(lease.page()).isNotNull();
            assertThat(lease.context()).isNotNull();
            assertThat(pool.activeLeases()).isEqualTo(1);
            assertThat(pool.availablePermits()).isZero();
            assertThat(pool.isSaturated()).isTrue();
        }
        assertThat(pool.activeLeases()).isZero();
        assertThat(pool.availablePermits()).isEqualTo(1);
    }

    @Test
    void capacityIsNeverExceeded_secondAcquireTimesOutRatherThanOverAllocating() {
        BrowserLeasePool pool = pool(1, 180, 1);
        try (ContextLease held = pool.acquire()) {
            assertThat(held).isNotNull();
            assertThatThrownBy(pool::acquire)
                    .isInstanceOf(BrowserLeasePool.BrowserCapacityUnavailableException.class)
                    .hasMessageContaining("no browser capacity");
        }
        // Exactly one context was ever created — the timeout did not silently allocate a second.
        verify(sessionManager, times(1)).newContext();
    }

    @Test
    void releasingFreesCapacityForTheNextCaller() {
        BrowserLeasePool pool = pool(1, 180, 5);
        ContextLease first = pool.acquire();
        first.close();
        try (ContextLease second = pool.acquire()) {
            assertThat(second).isNotNull();
        }
        verify(sessionManager, times(2)).newContext();
        verify(sessionManager, times(2)).contextClosed();
    }

    @Test
    void doubleCloseIsIdempotent_permitIsNotReturnedTwice() {
        BrowserLeasePool pool = pool(2, 180, 5);
        ContextLease lease = pool.acquire();
        lease.close();
        lease.close();
        lease.close();
        // Over-releasing would leave more permits than the pool owns and silently raise the memory cap.
        assertThat(pool.availablePermits()).isEqualTo(2);
        verify(sessionManager, times(1)).contextClosed();
    }

    @Test
    void releasedLeaseRejectsFurtherUse() {
        BrowserLeasePool pool = pool(1, 180, 5);
        ContextLease lease = pool.acquire();
        lease.close();
        assertThatThrownBy(lease::page)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been released");
    }

    @Test
    void unexpiredLeaseIsNotReclaimed() {
        BrowserLeasePool pool = pool(1, 300, 2);
        try (ContextLease held = pool.acquire()) {
            assertThat(pool.reclaimExpired()).isZero();
            assertThat(pool.availablePermits()).isZero();
            assertThat(held.isReleased()).isFalse();
        }
    }

    @Test
    void expiredLeaseIsReclaimed_soAForgottenSessionCannotHoldCapacityForever() throws Exception {
        BrowserLeasePool pool = pool(1, 1, 2); // 1s TTL
        ContextLease abandoned = pool.acquire();   // deliberately never closed
        assertThat(pool.availablePermits()).isZero();

        Thread.sleep(1100);

        assertThat(pool.reclaimExpired()).isEqualTo(1);
        assertThat(pool.availablePermits())
                .as("capacity abandoned by a caller that never released must come back")
                .isEqualTo(1);
        assertThat(pool.activeLeases()).isZero();
        assertThat(abandoned.isReleased()).isTrue();
        verify(sessionManager, times(1)).contextClosed();
    }

    @Test
    void lateCloseAfterReclaimDoesNotDoubleReleaseThePermit() throws Exception {
        BrowserLeasePool pool = pool(1, 1, 2);
        ContextLease abandoned = pool.acquire();
        Thread.sleep(1100);
        assertThat(pool.reclaimExpired()).isEqualTo(1);

        // The original owner finally gets around to closing. If this returned a second permit the
        // pool would grant more concurrent browsers than it owns — the exact failure mode the
        // registry's atomic remove exists to prevent.
        abandoned.close();

        assertThat(pool.availablePermits()).isEqualTo(1);
    }

    @Test
    void configFloorsRejectNonsenseWithoutDeadlocking() {
        BrowserLeasePool pool = pool(0, 0, 0);
        assertThat(pool.maxLeases()).isEqualTo(1);           // never zero — that would deadlock every job
        assertThat(pool.leaseTtl().toSeconds()).isEqualTo(1);
    }

    @Test
    void concurrentAcquiresNeverExceedMaxLeases() throws Exception {
        int maxLeases = 2;
        int threads = 12;
        BrowserLeasePool pool = pool(maxLeases, 180, 5);

        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pooledThreads = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pooledThreads.submit(() -> {
                try {
                    start.await();
                    try (ContextLease lease = pool.acquire()) {
                        int now = concurrent.incrementAndGet();
                        peak.updateAndGet(p -> Math.max(p, now));
                        succeeded.incrementAndGet();
                        Thread.sleep(20);
                        concurrent.decrementAndGet();
                    }
                } catch (BrowserLeasePool.BrowserCapacityUnavailableException expected) {
                    // Acceptable outcome under contention — it is a bound, not a queue.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pooledThreads.shutdownNow();

        assertThat(peak.get())
                .as("concurrent leases must never exceed the configured cap")
                .isLessThanOrEqualTo(maxLeases);
        assertThat(succeeded.get()).isPositive();
        // Every successful acquire returned its permit.
        assertThat(pool.availablePermits()).isEqualTo(maxLeases);
        assertThat(pool.activeLeases()).isZero();
    }

    @Test
    void failureCreatingThePageReturnsThePermitRatherThanLeakingCapacity() {
        BrowserContext broken = mock(BrowserContext.class);
        when(broken.newPage()).thenThrow(new IllegalStateException("renderer crashed"));
        when(sessionManager.newContext()).thenReturn(broken);

        BrowserLeasePool pool = pool(1, 180, 2);
        assertThatThrownBy(pool::acquire).isInstanceOf(IllegalStateException.class);

        assertThat(pool.availablePermits())
                .as("a failed acquire must not permanently consume capacity")
                .isEqualTo(1);
        assertThat(pool.activeLeases()).isZero();
        verify(broken, atLeastOnce()).close();
    }

    @Test
    void shutdownDrainsAndForceDestroysOutstandingLeases() {
        BrowserLeasePool pool = pool(2, 180, 2);
        ContextLease leaked = pool.acquire();
        assertThat(pool.activeLeases()).isEqualTo(1);

        pool.drainAndShutdown();

        assertThat(pool.activeLeases()).isZero();
        assertThatThrownBy(pool::acquire)
                .isInstanceOf(BrowserLeasePool.BrowserCapacityUnavailableException.class)
                .hasMessageContaining("shutting down");
        assertThat(leaked.isReleased()).isTrue();
    }

    @Test
    void snapshotExposesCapacityStateWithoutPageContent() {
        BrowserLeasePool pool = pool(2, 180, 5);
        try (ContextLease lease = pool.acquire()) {
            var snap = pool.snapshot();
            assertThat(snap).containsKeys("maxLeases", "activeLeases", "availablePermits",
                    "saturated", "leaseTtlSeconds", "active");
            assertThat(snap.get("activeLeases")).isEqualTo(1);
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> active =
                    (List<java.util.Map<String, Object>>) snap.get("active");
            assertThat(active).hasSize(1);
            assertThat(active.get(0)).containsKeys("leaseId", "ageMs", "ownerThread");
            assertThat(lease.id()).isNotNull();
        }
    }

    @Test
    void metricsRecordAcquisitionAndTimeouts() {
        BrowserLeasePool pool = pool(1, 180, 1);
        ContextLease held = pool.acquire();
        assertThatThrownBy(pool::acquire)
                .isInstanceOf(BrowserLeasePool.BrowserCapacityUnavailableException.class);
        held.close();

        var snap = metrics.snapshot();
        assertThat((Long) snap.get("poolAcquired")).isEqualTo(1L);
        assertThat((Long) snap.get("poolReleased")).isEqualTo(1L);
        assertThat((Long) snap.get("poolAcquireTimeouts")).isEqualTo(1L);
        assertThat((Long) snap.get("poolOutstanding")).isZero();
    }

    // ── P4 WI4 — a context counted but never handed out must still be uncounted ───────────────
    //
    // newContext() increments the session manager's open-context counter the moment it returns.
    // A failure AFTER that (newPage, setDefaultTimeout) previously returned the permit and closed
    // the context but never called contextClosed(), so the counter drifted up by one per failed
    // acquire and never came back down. At five it makes isZombie() permanently true — the
    // maintenance sweep then restarts a healthy browser every minute — and it pins recycleIfDue()
    // at DEFERRED_CONTEXT_OPEN, silently disabling idle shutdown with no error anywhere.

    @Test
    void aFailureAfterContextCreationStillNotifiesContextClosed() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.newPage()).thenThrow(new IllegalStateException("Target page, context or browser has been closed"));
        when(sessionManager.newContext()).thenReturn(ctx);

        BrowserLeasePool pool = pool(1, 60, 1);

        assertThatThrownBy(pool::acquire).isInstanceOf(IllegalStateException.class);

        // The counter is restored...
        verify(sessionManager, times(1)).contextClosed();
        // ...the context is destroyed...
        verify(ctx, times(1)).close();
        // ...and the permit is back, so the pool has not lost capacity either.
        assertThat(pool.availablePermits()).isEqualTo(1);
        assertThat(pool.activeLeases()).isZero();
    }

    @Test
    void repeatedFailedAcquiresNeverAccumulateContextCount() {
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.newPage()).thenThrow(new IllegalStateException("renderer crashed"));
        when(sessionManager.newContext()).thenReturn(ctx);

        BrowserLeasePool pool = pool(1, 60, 1);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(pool::acquire).isInstanceOf(IllegalStateException.class);
        }

        // One decrement per failed acquire — the counter cannot drift toward the zombie threshold.
        verify(sessionManager, times(5)).contextClosed();
        assertThat(pool.availablePermits()).isEqualTo(1);
    }

    @Test
    void aFailureBeforeContextCreationDoesNotNotifyContextClosed() {
        // newContext() itself threw, so nothing was ever counted — notifying would push the
        // counter negative-ward and is just as wrong as not notifying when it was.
        when(sessionManager.newContext()).thenThrow(new IllegalStateException("browser not launched"));

        BrowserLeasePool pool = pool(1, 60, 1);

        assertThatThrownBy(pool::acquire).isInstanceOf(IllegalStateException.class);

        verify(sessionManager, never()).contextClosed();
        assertThat(pool.availablePermits()).isEqualTo(1);
    }
}
