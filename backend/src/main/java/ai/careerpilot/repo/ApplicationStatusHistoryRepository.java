package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, UUID> {

    List<ApplicationStatusHistory> findByLifecycleIdOrderByChangedAtDesc(UUID lifecycleId);

    /** Row shape for {@link #findLatestChangePerLifecycle}. */
    interface LatestChange {
        UUID getLifecycleId();
        java.time.Instant getChangedAt();
        /** P1.1 — the status this lifecycle transitioned FROM on its most recent change; null for
         *  the very first history row (nothing preceded it). */
        String getFromStatus();
    }

    /**
     * The newest {@code changed_at} (+ {@code from_status}, for "previous status") per lifecycle,
     * for a whole page of cards in one query. The card only ever reads the first element of the
     * descending history, so nothing else is fetched.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT DISTINCT ON (lifecycle_id) lifecycle_id AS lifecycleId, changed_at AS changedAt,
                   from_status AS fromStatus
            FROM application_status_history
            WHERE lifecycle_id IN (:lifecycleIds)
            ORDER BY lifecycle_id, changed_at DESC
            """, nativeQuery = true)
    List<LatestChange> findLatestChangePerLifecycle(
            @org.springframework.data.repository.query.Param("lifecycleIds") java.util.Collection<UUID> lifecycleIds);
}
