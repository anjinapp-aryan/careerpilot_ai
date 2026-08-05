package ai.careerpilot.repo;

import ai.careerpilot.domain.CandidateAtsProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Phase C — one ATS profile row per user. */
public interface CandidateAtsProfileRepository extends JpaRepository<CandidateAtsProfile, UUID> {

    Optional<CandidateAtsProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
