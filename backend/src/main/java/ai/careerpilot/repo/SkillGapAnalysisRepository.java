package ai.careerpilot.repo;

import ai.careerpilot.domain.SkillGapAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SkillGapAnalysisRepository extends JpaRepository<SkillGapAnalysis, UUID> {

    List<SkillGapAnalysis> findByMissionIdAndUserIdOrderByCreatedAtDesc(UUID missionId, UUID userId);

    Optional<SkillGapAnalysis> findFirstByMissionIdAndUserIdOrderByCreatedAtDesc(UUID missionId, UUID userId);

    Optional<SkillGapAnalysis> findByIdAndUserId(UUID id, UUID userId);
}
