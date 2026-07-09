package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationReviewRepository extends JpaRepository<ApplicationReview, UUID> {

    Optional<ApplicationReview> findByApplicationPackageId(UUID applicationPackageId);

    List<ApplicationReview> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    long countByVerdict(String verdict);

    long countByQualityCategory(String qualityCategory);
}
