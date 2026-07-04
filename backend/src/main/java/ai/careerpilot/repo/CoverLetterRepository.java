package ai.careerpilot.repo;

import ai.careerpilot.domain.CoverLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoverLetterRepository extends JpaRepository<CoverLetter, UUID> {

    Optional<CoverLetter> findByUserIdAndJobId(UUID userId, UUID jobId);
}
