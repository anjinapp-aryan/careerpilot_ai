package ai.careerpilot.repo;

import ai.careerpilot.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {
    List<Resume> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Distinct users who have at least one resume — the backfill candidate set. */
    @Query("select distinct r.userId from Resume r")
    List<UUID> findDistinctUserIds();

    /**
     * Set a resume's embedding from a pgvector literal. The vector(768) `embedding` column is not
     * mapped on the Resume entity (no pgvector Hibernate type), so it is written via native SQL.
     */
    @Modifying
    @Query(value = "UPDATE resumes SET embedding = CAST(:vec AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("vec") String vec);
}
