package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationSubmissionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationSubmissionAnswerRepository extends JpaRepository<ApplicationSubmissionAnswer, UUID> {

    List<ApplicationSubmissionAnswer> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
