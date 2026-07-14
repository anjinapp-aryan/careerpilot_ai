package ai.careerpilot.jobdiscovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider-level observability layer for job discovery — additive, does not touch any existing
 * provider's {@code fetch()} body. Mirrors the shape of {@link ai.careerpilot.ai.ProviderHealthTracker}
 * (the AI Gateway's per-provider health cache) but is job-discovery-scoped: it is a separate class,
 * not shared code, tracking latency/success-failure/jobs-fetched/last-run per job source provider,
 * backed by an in-memory cache (this is a dark-flagged, diagnostics-only feature — no migration
 * needed, matching the AI Gateway's TTL-cache approach for its own in-memory health map).
 *
 * <p><b>Circuit breaker scope note:</b> this tracks a simple CLOSED/OPEN/HALF_OPEN state field per
 * provider (open after {@link #FAILURE_THRESHOLD} consecutive failures, half-open after a cooldown)
 * for diagnostics/reporting purposes. It does not gate {@link JobAggregationService#runProvider}
 * calls — full Resilience4j circuit-breaker wiring per job-provider was judged disproportionate to
 * this scope (unlike the AI Gateway, a job-discovery provider failing once a day costs nothing but
 * one skipped run; there is no user-facing latency to protect). {@code JobAggregationService}
 * already isolates provider failures via its own try/catch and writes a FAILED audit row — this
 * tracker is purely additive observability on top of that existing isolation.
 */
@Service
public class JobDiscoveryHealthTracker {

    private static final Logger log = LoggerFactory.getLogger(JobDiscoveryHealthTracker.class);

    /** Consecutive failures before a provider's circuit is marked OPEN. */
    static final int FAILURE_THRESHOLD = 3;

    private final Map<String, ProviderStats> stats = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public JobDiscoveryHealthTracker(
            @Value("${career.discovery.health.circuit-cooldown-ms:300000}") long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    /** Immutable point-in-time view returned to diagnostics callers. */
    public record Snapshot(
            String provider,
            CircuitState circuitState,
            long totalRuns,
            long successRuns,
            long failureRuns,
            long lastLatencyMs,
            long avgLatencyMs,
            long lastJobsFetched,
            long lastJobsAccepted,
            long lastJobsRejected,
            Instant lastRunAt,
            String lastError) {

        public double successRate() {
            return totalRuns == 0 ? 0.0 : (double) successRuns / totalRuns;
        }
    }

    public void recordSuccess(String provider, long latencyMs, int jobsFetched, int jobsAccepted, int jobsRejected) {
        ProviderStats s = stats.computeIfAbsent(provider, p -> new ProviderStats());
        synchronized (s) {
            s.totalRuns.incrementAndGet();
            s.successRuns.incrementAndGet();
            s.consecutiveFailures = 0;
            s.lastLatencyMs = latencyMs;
            s.totalLatencyMs.addAndGet(latencyMs);
            s.lastJobsFetched = jobsFetched;
            s.lastJobsAccepted = jobsAccepted;
            s.lastJobsRejected = jobsRejected;
            s.lastRunAt = Instant.now();
            s.lastError = null;
            if (s.circuitState == CircuitState.HALF_OPEN || s.circuitState == CircuitState.OPEN) {
                s.circuitState = CircuitState.CLOSED;
            }
        }
        log.debug("Provider {} health: SUCCESS latency={}ms fetched={} accepted={} rejected={}",
                provider, latencyMs, jobsFetched, jobsAccepted, jobsRejected);
    }

    public void recordFailure(String provider, long latencyMs, String reason) {
        ProviderStats s = stats.computeIfAbsent(provider, p -> new ProviderStats());
        synchronized (s) {
            s.totalRuns.incrementAndGet();
            s.failureRuns.incrementAndGet();
            s.consecutiveFailures++;
            s.lastLatencyMs = latencyMs;
            s.totalLatencyMs.addAndGet(latencyMs);
            s.lastRunAt = Instant.now();
            s.lastError = reason;
            if (s.consecutiveFailures >= FAILURE_THRESHOLD) {
                s.circuitState = CircuitState.OPEN;
                s.circuitOpenedAt = Instant.now();
            }
        }
        log.warn("Provider {} health: FAILURE latency={}ms reason={}", provider, latencyMs, reason);
    }

    public Snapshot snapshot(String provider) {
        ProviderStats s = stats.get(provider);
        if (s == null) {
            return new Snapshot(provider, CircuitState.CLOSED, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
        synchronized (s) {
            resolveHalfOpen(s);
            long avg = s.totalRuns.get() == 0 ? 0 : s.totalLatencyMs.get() / s.totalRuns.get();
            return new Snapshot(provider, s.circuitState, s.totalRuns.get(), s.successRuns.get(),
                    s.failureRuns.get(), s.lastLatencyMs, avg, s.lastJobsFetched, s.lastJobsAccepted,
                    s.lastJobsRejected, s.lastRunAt, s.lastError);
        }
    }

    /** All tracked providers' snapshots, keyed by provider name, insertion order preserved. */
    public Map<String, Snapshot> allSnapshots() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (String provider : stats.keySet()) {
            out.put(provider, snapshot(provider));
        }
        return out;
    }

    /** Reset a provider's tracked stats — used by tests and manual diagnostics recovery. */
    public void reset(String provider) {
        stats.remove(provider);
    }

    private void resolveHalfOpen(ProviderStats s) {
        if (s.circuitState == CircuitState.OPEN && s.circuitOpenedAt != null) {
            if (Duration.between(s.circuitOpenedAt, Instant.now()).toMillis() >= cooldownMs) {
                s.circuitState = CircuitState.HALF_OPEN;
            }
        }
    }

    private static final class ProviderStats {
        final AtomicLong totalRuns = new AtomicLong();
        final AtomicLong successRuns = new AtomicLong();
        final AtomicLong failureRuns = new AtomicLong();
        final AtomicLong totalLatencyMs = new AtomicLong();
        volatile int consecutiveFailures = 0;
        volatile long lastLatencyMs = 0;
        volatile long lastJobsFetched = 0;
        volatile long lastJobsAccepted = 0;
        volatile long lastJobsRejected = 0;
        volatile Instant lastRunAt;
        volatile String lastError;
        volatile CircuitState circuitState = CircuitState.CLOSED;
        volatile Instant circuitOpenedAt;
    }
}
