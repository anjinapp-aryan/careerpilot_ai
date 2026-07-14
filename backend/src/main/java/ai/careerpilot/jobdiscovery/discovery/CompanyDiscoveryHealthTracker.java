package ai.careerpilot.jobdiscovery.discovery;

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
 * Gap A — Company Discovery Agent. Per-{@link CompanySource} observability layer, mirroring the
 * shape of {@link ai.careerpilot.jobdiscovery.JobDiscoveryHealthTracker} (same CLOSED/OPEN/
 * HALF_OPEN circuit-state idea, same in-memory ConcurrentHashMap-backed cache, same diagnostics-
 * only scope — this does not gate {@link CompanyDiscoveryService} calls) — but its own separate
 * instance/class, since company discovery is a distinct concern from job-listing discovery (see
 * CLAUDE.md's "provisioned but unused" partitioning convention: sibling features get sibling
 * trackers, not a shared one). Counts here are probe-shaped (candidates probed / hits / misses /
 * failures) rather than job-fetch-shaped.
 */
@Service
public class CompanyDiscoveryHealthTracker {

    private static final Logger log = LoggerFactory.getLogger(CompanyDiscoveryHealthTracker.class);

    static final int FAILURE_THRESHOLD = 3;

    private final Map<String, SourceStats> stats = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public CompanyDiscoveryHealthTracker(
            @Value("${company.discovery.health.circuit-cooldown-ms:300000}") long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public record Snapshot(
            String source,
            CircuitState circuitState,
            long totalProbes,
            long hits,
            long misses,
            long failures,
            long lastLatencyMs,
            long avgLatencyMs,
            Instant lastRunAt,
            String lastError) {

        public double hitRate() {
            return totalProbes == 0 ? 0.0 : (double) hits / totalProbes;
        }
    }

    public void recordHit(String source, long latencyMs) {
        SourceStats s = stats.computeIfAbsent(source, k -> new SourceStats());
        synchronized (s) {
            s.totalProbes.incrementAndGet();
            s.hits.incrementAndGet();
            s.consecutiveFailures = 0;
            touchLatency(s, latencyMs);
            s.lastError = null;
            if (s.circuitState == CircuitState.HALF_OPEN || s.circuitState == CircuitState.OPEN) {
                s.circuitState = CircuitState.CLOSED;
            }
        }
        log.debug("company-discovery source {} probe HIT latency={}ms", source, latencyMs);
    }

    public void recordMiss(String source, long latencyMs) {
        SourceStats s = stats.computeIfAbsent(source, k -> new SourceStats());
        synchronized (s) {
            s.totalProbes.incrementAndGet();
            s.misses.incrementAndGet();
            s.consecutiveFailures = 0;
            touchLatency(s, latencyMs);
            s.lastError = null;
            if (s.circuitState == CircuitState.HALF_OPEN || s.circuitState == CircuitState.OPEN) {
                s.circuitState = CircuitState.CLOSED;
            }
        }
    }

    public void recordFailure(String source, long latencyMs, String reason) {
        SourceStats s = stats.computeIfAbsent(source, k -> new SourceStats());
        synchronized (s) {
            s.totalProbes.incrementAndGet();
            s.failures.incrementAndGet();
            s.consecutiveFailures++;
            touchLatency(s, latencyMs);
            s.lastError = reason;
            if (s.consecutiveFailures >= FAILURE_THRESHOLD) {
                s.circuitState = CircuitState.OPEN;
                s.circuitOpenedAt = Instant.now();
            }
        }
        log.warn("company-discovery source {} probe FAILURE latency={}ms reason={}", source, latencyMs, reason);
    }

    public Snapshot snapshot(String source) {
        SourceStats s = stats.get(source);
        if (s == null) {
            return new Snapshot(source, CircuitState.CLOSED, 0, 0, 0, 0, 0, 0, null, null);
        }
        synchronized (s) {
            resolveHalfOpen(s);
            long avg = s.totalProbes.get() == 0 ? 0 : s.totalLatencyMs.get() / s.totalProbes.get();
            return new Snapshot(source, s.circuitState, s.totalProbes.get(), s.hits.get(), s.misses.get(),
                    s.failures.get(), s.lastLatencyMs, avg, s.lastRunAt, s.lastError);
        }
    }

    public Map<String, Snapshot> allSnapshots() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (String source : stats.keySet()) {
            out.put(source, snapshot(source));
        }
        return out;
    }

    public void reset(String source) {
        stats.remove(source);
    }

    private void touchLatency(SourceStats s, long latencyMs) {
        s.lastLatencyMs = latencyMs;
        s.totalLatencyMs.addAndGet(latencyMs);
        s.lastRunAt = Instant.now();
    }

    private void resolveHalfOpen(SourceStats s) {
        if (s.circuitState == CircuitState.OPEN && s.circuitOpenedAt != null) {
            if (Duration.between(s.circuitOpenedAt, Instant.now()).toMillis() >= cooldownMs) {
                s.circuitState = CircuitState.HALF_OPEN;
            }
        }
    }

    private static final class SourceStats {
        final AtomicLong totalProbes = new AtomicLong();
        final AtomicLong hits = new AtomicLong();
        final AtomicLong misses = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong totalLatencyMs = new AtomicLong();
        volatile int consecutiveFailures = 0;
        volatile long lastLatencyMs = 0;
        volatile Instant lastRunAt;
        volatile String lastError;
        volatile CircuitState circuitState = CircuitState.CLOSED;
        volatile Instant circuitOpenedAt;
    }
}
