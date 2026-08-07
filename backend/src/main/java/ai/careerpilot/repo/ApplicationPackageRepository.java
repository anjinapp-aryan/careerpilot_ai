package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApplicationPackageRepository extends JpaRepository<ApplicationPackage, UUID> {

    Optional<ApplicationPackage> findByUserIdAndJobId(UUID userId, UUID jobId);

    /**
     * Claim the single head row for a (user, job) pair, tolerating a concurrent claim.
     *
     * <p><b>Why this exists.</b> {@code ApplicationPackageService.assemble} used to
     * find-then-insert, which is safe only when one thread runs it. It is reached from the async
     * Phase 2D pipeline, from the submission session, and from package intelligence — so two runs
     * for the same (user, job) genuinely overlap, both observe no head, and both insert. The loser
     * dies on {@code uq_application_package_user_job}, killing the whole pipeline with a
     * {@code DataIntegrityViolationException}. Observed in production use: a real pair ended up with
     * one {@code v1} row and a failed run, both having computed version 1.
     *
     * <p>{@code ON CONFLICT DO NOTHING} moves the decision into the database, where the unique
     * constraint already is. The loser inserts nothing and raises nothing, and both threads then
     * read back the same row — so the race resolves to "one head exists" rather than an exception.
     *
     * <p>Deliberately writes only the NOT NULL columns and leaves {@code package_version} at 0:
     * this claims the row's identity, it does not assemble it. The caller immediately loads the row
     * and populates it, bumping the version as it always did — so a freshly claimed head is never
     * mistaken for an assembled package.
     *
     * @return 1 when this caller created the row, 0 when another had already claimed it
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO application_package (id, user_id, job_id, package_version, status, created_at, updated_at)
            VALUES (gen_random_uuid(), :userId, :jobId, 0, 'INCOMPLETE', now(), now())
            ON CONFLICT (user_id, job_id) DO NOTHING
            """, nativeQuery = true)
    int claimHeadIfAbsent(@org.springframework.data.repository.query.Param("userId") UUID userId,
                          @org.springframework.data.repository.query.Param("jobId") UUID jobId);

    /** Row shape for {@link #findRefsByUserIdAndJobIdIn}: just the identity a card needs. */
    interface PackageRef {
        UUID getId();
        UUID getJobId();
    }

    /**
     * Bulk form of {@link #findByUserIdAndJobId} for card assembly — the card needs only "does a
     * package exist" plus its id (to look up a review), so this projects two columns rather than
     * loading the metadata/summary text blobs for a whole page.
     */
    @org.springframework.data.jpa.repository.Query(
            "select p.id as id, p.jobId as jobId from ApplicationPackage p "
                    + "where p.userId = :userId and p.jobId in :jobIds")
    java.util.List<PackageRef> findRefsByUserIdAndJobIdIn(
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("jobIds") java.util.Collection<UUID> jobIds);

    Optional<ApplicationPackage> findFirstByApplicationIdOrderByPackageVersionDesc(UUID applicationId);

    java.util.List<ApplicationPackage> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    long countByValidationStatus(String validationStatus);
}
