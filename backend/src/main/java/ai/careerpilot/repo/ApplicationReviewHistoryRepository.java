package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationReviewHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationReviewHistoryRepository extends JpaRepository<ApplicationReviewHistory, UUID> {

    List<ApplicationReviewHistory> findByApplicationReviewIdOrderByCreatedAtDesc(UUID applicationReviewId);

    List<ApplicationReviewHistory> findByApplicationPackageIdOrderByCreatedAtDesc(UUID applicationPackageId);
}
