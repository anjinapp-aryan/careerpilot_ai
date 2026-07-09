package ai.careerpilot.repo;

import ai.careerpilot.domain.InterviewFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewFeedbackRepository extends JpaRepository<InterviewFeedback, UUID> {

    List<InterviewFeedback> findByInterviewId(UUID interviewId);
}
