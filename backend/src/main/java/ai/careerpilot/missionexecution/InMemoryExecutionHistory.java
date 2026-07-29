package ai.careerpilot.missionexecution;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pre-Phase-9 Hardening — the only {@link ExecutionHistory}: an unbounded-until-capacity, per-mission ring buffer, same shape as {@code ai.careerpilot.career.agent.InMemoryAgentMemory}. */
public class InMemoryExecutionHistory implements ExecutionHistory {

    private static final int MAX_PER_MISSION = 50;

    private final Map<UUID, CopyOnWriteArrayList<MissionExecutionPlan>> byMission = new ConcurrentHashMap<>();

    @Override
    public void remember(MissionExecutionPlan plan) {
        CopyOnWriteArrayList<MissionExecutionPlan> list =
                byMission.computeIfAbsent(plan.missionId(), k -> new CopyOnWriteArrayList<>());
        list.add(0, plan);
        while (list.size() > MAX_PER_MISSION) {
            list.remove(list.size() - 1);
        }
    }

    @Override
    public List<MissionExecutionPlan> recentFor(UUID missionId, int limit) {
        List<MissionExecutionPlan> list = byMission.getOrDefault(missionId, new CopyOnWriteArrayList<>());
        return list.stream().limit(limit).toList();
    }
}
