package ai.careerpilot.repo;

import ai.careerpilot.domain.InterviewTimeline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewTimelineRepository extends JpaRepository<InterviewTimeline, UUID> {

    List<InterviewTimeline> findByInterviewIdOrderByOccurredAtDesc(UUID interviewId);
}
