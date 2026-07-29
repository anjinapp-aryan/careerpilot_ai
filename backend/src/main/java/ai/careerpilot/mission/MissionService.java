package ai.careerpilot.mission;

import ai.careerpilot.api.dto.MissionDtos.MissionRequest;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.repo.CareerMissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Mission Engine, Phase 1 — plain CRUD over {@link CareerMission}, ownership-scoped by {@code
 * userId} (same manual-isolation convention as every other service in this codebase — see
 * CLAUDE.md's "JWT auth carries multi-tenant context"). No caching, no events, no state machine:
 * status transitions (ACTIVE/PAUSED/COMPLETED) are just a field update via {@link
 * #update}, not modeled as a separate workflow.
 */
@Service
public class MissionService {

    private final CareerMissionRepository missions;

    public MissionService(CareerMissionRepository missions) {
        this.missions = missions;
    }

    @Transactional
    public CareerMission create(UUID userId, MissionRequest request) {
        return missions.save(request.toEntity(userId));
    }

    public CareerMission get(UUID userId, UUID missionId) {
        return missions.findByIdAndUserId(missionId, userId)
                .orElseThrow(() -> new MissionNotFoundException(missionId));
    }

    public List<CareerMission> list(UUID userId) {
        return missions.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public CareerMission update(UUID userId, UUID missionId, MissionRequest request) {
        CareerMission mission = get(userId, missionId);
        request.applyTo(mission);
        return missions.save(mission);
    }

    @Transactional
    public void delete(UUID userId, UUID missionId) {
        CareerMission mission = get(userId, missionId);
        missions.delete(mission);
    }
}
