package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationReviewRepository extends JpaRepository<ApplicationReview, UUID> {

    Optional<ApplicationReview> findByApplicationPackageId(UUID applicationPackageId);

    /** Bulk presence check for {@link #findByApplicationPackageId} — one query per page of cards. */
    @org.springframework.data.jpa.repository.Query(
            "select r.applicationPackageId from ApplicationReview r where r.applicationPackageId in :packageIds")
    List<UUID> findReviewedPackageIds(
            @org.springframework.data.repository.query.Param("packageIds") java.util.Collection<UUID> packageIds);

    List<ApplicationReview> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    long countByVerdict(String verdict);

    long countByQualityCategory(String qualityCategory);
}
