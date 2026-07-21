package ai.careerpilot.repo;

import ai.careerpilot.domain.InterviewFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewFeedbackRepository extends JpaRepository<InterviewFeedback, UUID> {

    List<InterviewFeedback> findByInterviewId(UUID interviewId);

    /** Phase 7.15.3A — batch fetch, avoids N+1 when rendering a user's full interview list. */
    List<InterviewFeedback> findByInterviewIdIn(java.util.Collection<UUID> interviewIds);
}
