package ai.careerpilot.repo;

import ai.careerpilot.domain.EmployerQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Phase D — the deduplicated employer question library. */
public interface EmployerQuestionRepository extends JpaRepository<EmployerQuestion, UUID> {

    Optional<EmployerQuestion> findByNormalizedText(String normalizedText);

    List<EmployerQuestion> findByQuestionCategory(String questionCategory);

    /** Bounded, matching this codebase's findTop-N convention rather than an unbounded read. */
    List<EmployerQuestion> findTop200ByOrderByLastSeenAtDesc();
}
