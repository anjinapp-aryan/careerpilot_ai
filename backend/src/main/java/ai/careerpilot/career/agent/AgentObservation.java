package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerAlert;
import ai.careerpilot.career.monitor.CareerInsights;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.6 — what the agent perceived before planning. {@code signals} reuses {@code
 * ai.careerpilot.career.monitor.CareerAlert} directly (Phase 11.5) rather than duplicating that
 * shape — the agent's "senses" are the same proactive-intelligence signals a human would see,
 * not a separate data source. {@link #from} is the normal construction path (wrapping a real
 * {@link CareerInsights} run); {@link #empty} is used when Phase 11.5's {@code CareerMonitor}
 * bean isn't available (its own independent {@code career.monitor.enabled} flag is off) —
 * observing nothing is a valid, honest state, not an error.
 */
public record AgentObservation(UUID userId, Instant observedAt, List<CareerAlert> signals, String source) {

    public static AgentObservation from(UUID userId, CareerInsights insights) {
        return new AgentObservation(userId, Instant.now(), insights.recommendations(), "CareerMonitor");
    }

    public static AgentObservation empty(UUID userId) {
        return new AgentObservation(userId, Instant.now(), List.of(), "none (CareerMonitor unavailable)");
    }
}
