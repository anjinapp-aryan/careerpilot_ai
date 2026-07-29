package ai.careerpilot.career.agent;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7A — Mission-Aware Autonomous Career Agent. What the agent perceived about the user's
 * active Mission, sourced entirely from {@code ai.careerpilot.mission.MissionAwareDailyBriefService}
 * (Phase 6A) — no new business logic here, just a read of the exact same composed brief the Daily
 * Coach already shows the user. {@code recommendedWorkflowIds} is that brief's {@code
 * priorityWorkflows()} verbatim — the Mission Orchestrator's (Phase 5) own "what should run next"
 * decision list. This record only carries data; it makes no decisions.
 */
public record MissionContext(UUID missionId, String missionName, int progressPercent,
                              String currentStrategyCountry, List<String> highRiskAreas,
                              List<String> recommendedWorkflowIds) {}
