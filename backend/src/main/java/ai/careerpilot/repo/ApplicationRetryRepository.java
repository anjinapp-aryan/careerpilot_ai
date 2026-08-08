package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationRetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationRetryRepository extends JpaRepository<ApplicationRetry, UUID> {

    List<ApplicationRetry> findByApplicationExecutionIdOrderByAttemptAsc(UUID applicationExecutionId);

    long countByApplicationExecutionId(UUID applicationExecutionId);

    /** Row shape for {@link #countPerExecution}. */
    interface RetryCount {
        UUID getExecutionId();
        Long getCnt();
    }

    /** Bulk form of {@link #countByApplicationExecutionId} — one grouped query per page of cards. */
    @org.springframework.data.jpa.repository.Query(
            "select r.applicationExecutionId as executionId, count(r) as cnt from ApplicationRetry r "
                    + "where r.applicationExecutionId in :executionIds group by r.applicationExecutionId")
    List<RetryCount> countPerExecution(
            @org.springframework.data.repository.query.Param("executionIds") java.util.Collection<UUID> executionIds);
}
