package ai.careerpilot.repo;

import ai.careerpilot.domain.DailyCareerSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyCareerSummaryRepository extends JpaRepository<DailyCareerSummary, UUID> {
    Optional<DailyCareerSummary> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
    List<DailyCareerSummary> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
