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
 *
 * <p><b>Phase 7A</b> — {@code missionContext} is {@code null} unless {@code
 * career.mission.agent.enabled} is on and the user has an active Mission; {@link
 * #withMissionContext} attaches it without disturbing the {@code CareerMonitor}-derived fields.
 * The 4-arg constructor below is preserved so every pre-7A call site (tests included) keeps
 * compiling unchanged, always producing a {@code null} mission context — identical to pre-7A
 * behavior.
 */
public record AgentObservation(UUID userId, Instant observedAt, List<CareerAlert> signals, String source,
                                MissionContext missionContext) {

    public AgentObservation(UUID userId, Instant observedAt, List<CareerAlert> signals, String source) {
        this(userId, observedAt, signals, source, null);
    }

    public static AgentObservation from(UUID userId, CareerInsights insights) {
        return new AgentObservation(userId, Instant.now(), insights.recommendations(), "CareerMonitor");
    }

    public static AgentObservation empty(UUID userId) {
        return new AgentObservation(userId, Instant.now(), List.of(), "none (CareerMonitor unavailable)");
    }

    public AgentObservation withMissionContext(MissionContext missionContext) {
        return new AgentObservation(userId, observedAt, signals, source, missionContext);
    }
}
