package ai.careerpilot.repo;

import ai.careerpilot.domain.CandidateProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the canonical {@link CandidateProfile}. One row per user, so reads/writes
 * are implicitly user-scoped (no cross-tenant leakage) — same pattern as
 * {@link CandidatePreferencesRepository}.
 */
public interface CandidateProfileRepository extends JpaRepository<CandidateProfile, UUID> {

    Optional<CandidateProfile> findByUserId(UUID userId);
}
