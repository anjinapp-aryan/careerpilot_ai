package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationTimeline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationTimelineRepository extends JpaRepository<ApplicationTimeline, UUID> {

    List<ApplicationTimeline> findByUserIdAndJobIdOrderByOccurredAtDesc(UUID userId, UUID jobId);

    /** Phase 10H — Career Timeline aggregation: every application event for this user, across all jobs. */
    List<ApplicationTimeline> findByUserIdOrderByOccurredAtDesc(UUID userId);

    long count();
}
