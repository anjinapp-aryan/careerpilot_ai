package ai.careerpilot.repo;

import ai.careerpilot.domain.CoverLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoverLetterRepository extends JpaRepository<CoverLetter, UUID> {

    Optional<CoverLetter> findByUserIdAndJobId(UUID userId, UUID jobId);

    /**
     * Which of these jobs have a cover letter at all — presence only, in one query. Projects
     * {@code jobId} rather than entities so a page of cards never loads a page of letter bodies.
     */
    @org.springframework.data.jpa.repository.Query(
            "select c.jobId from CoverLetter c where c.userId = :userId and c.jobId in :jobIds")
    java.util.List<UUID> findJobIdsWithCoverLetter(
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("jobIds") java.util.Collection<UUID> jobIds);
}
