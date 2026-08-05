package ai.careerpilot.execution.browser.pool;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Enterprise Browser Automation — one exclusive, time-bounded grant of browser capacity.
 *
 * <p><b>What is pooled is the permit, not the context.</b> The {@link BrowserContext} inside a
 * lease is always created fresh on acquire and destroyed on release; it is never handed to a second
 * job. That is deliberate and non-negotiable: a {@code BrowserContext} carries cookies, localStorage
 * and cache, so reusing one across users would leak one applicant's session into another's
 * application — precisely the multi-tenant boundary this codebase enforces by hand everywhere else.
 * Pooling the <em>permit</em> is what bounds memory; recreating the <em>context</em> is what keeps
 * users isolated. The two goals are met by different mechanisms on purpose.
 *
 * <p>{@link AutoCloseable} so callers can use try-with-resources, but the pool also tracks every
 * outstanding lease in a registry and will reclaim one that outlives its TTL — a caller that
 * forgets to close cannot permanently consume capacity.
 */
public final class ContextLease implements AutoCloseable {

    private final UUID id = UUID.randomUUID();
    private final BrowserContext context;
    private final Page page;
    private final Instant acquiredAt = Instant.now();
    private final String ownerThread = Thread.currentThread().getName();
    private final BrowserLeasePool pool;

    private volatile boolean released;

    ContextLease(BrowserContext context, Page page, BrowserLeasePool pool) {
        this.context = context;
        this.page = page;
        this.pool = pool;
    }

    public UUID id() { return id; }
    public Instant acquiredAt() { return acquiredAt; }
    public String ownerThread() { return ownerThread; }
    public boolean isReleased() { return released; }

    public Duration age() {
        return Duration.between(acquiredAt, Instant.now());
    }

    public BrowserContext context() {
        requireLive();
        return context;
    }

    public Page page() {
        requireLive();
        return page;
    }

    private void requireLive() {
        if (released) {
            throw new IllegalStateException("browser lease " + id + " has already been released");
        }
    }

    /** Idempotent — releasing twice is a no-op, so try-with-resources plus an explicit close is safe. */
    @Override
    public void close() {
        if (released) return;
        released = true;
        pool.release(this);
    }

    /** Package-private teardown used by the pool; closes the page then the context, never throwing. */
    void destroy(org.slf4j.Logger log) {
        try {
            if (page != null && !page.isClosed()) page.close();
        } catch (Exception e) {
            log.warn("BROWSER_LEASE page close failed lease={}: {}", id, e.toString());
        }
        try {
            if (context != null) context.close();
        } catch (Exception e) {
            log.warn("BROWSER_LEASE context close failed lease={}: {}", id, e.toString());
        }
    }

    /** Marks a lease released without invoking the pool again — used when the pool reclaims it. */
    void markReleased() {
        released = true;
    }
}
