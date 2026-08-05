package ai.careerpilot.repo;

import ai.careerpilot.domain.EmployerAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Phase D — reusable, human-approved answers. */
public interface EmployerAnswerRepository extends JpaRepository<EmployerAnswer, UUID> {

    Optional<EmployerAnswer> findByUserIdAndQuestionId(UUID userId, UUID questionId);

    List<EmployerAnswer> findByUserIdAndApproved(UUID userId, boolean approved);

    List<EmployerAnswer> findByUserId(UUID userId);
}
