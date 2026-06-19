package ai.careerpilot.repo;

import ai.careerpilot.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Query(value = "SELECT j.* FROM jobs j WHERE j.org_id = :orgId AND (:q IS NULL " +
                   "OR LOWER(j.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
                   "OR LOWER(j.company) LIKE LOWER(CONCAT('%', :q, '%')))",
           countQuery = "SELECT COUNT(*) FROM jobs j WHERE j.org_id = :orgId AND (:q IS NULL " +
                        "OR LOWER(j.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
                        "OR LOWER(j.company) LIKE LOWER(CONCAT('%', :q, '%')))",
           nativeQuery = true)
    Page<Job> search(@Param("orgId") UUID orgId, @Param("q") String q, Pageable pageable);

    Optional<Job> findByIdAndOrgId(UUID id, UUID orgId);
}
