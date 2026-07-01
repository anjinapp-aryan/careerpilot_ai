package ai.careerpilot.repo;

import ai.careerpilot.domain.CandidateBehaviorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** One behavior-profile row per user (Phase 2C-5). */
public interface CandidateBehaviorProfileRepository extends JpaRepository<CandidateBehaviorProfile, UUID> {
}
