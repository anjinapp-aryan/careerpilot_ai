package ai.careerpilot.repo;

import ai.careerpilot.domain.InternationalJobRanking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternationalJobRankingRepository extends JpaRepository<InternationalJobRanking, UUID> {

    List<InternationalJobRanking> findByUserIdOrderByRankScoreDesc(UUID userId);

    Page<InternationalJobRanking> findByUserIdOrderByRankScoreDesc(UUID userId, Pageable pageable);

    Optional<InternationalJobRanking> findByUserIdAndJobId(UUID userId, UUID jobId);

    @Modifying
    @Query("DELETE FROM InternationalJobRanking r WHERE r.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
