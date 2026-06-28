package ai.careerpilot.repo;

import ai.careerpilot.domain.CandidateProfileVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only audit history for {@link CandidateProfileVersion}. Reads are user-scoped by the
 * caller; rows are never updated or deleted in normal operation.
 */
public interface CandidateProfileVersionRepository extends JpaRepository<CandidateProfileVersion, UUID> {

    List<CandidateProfileVersion> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
