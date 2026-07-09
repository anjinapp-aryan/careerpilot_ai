package ai.careerpilot.repo;

import ai.careerpilot.domain.DailyDiscoveryRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DailyDiscoveryRunRepository extends JpaRepository<DailyDiscoveryRun, UUID> {
    List<DailyDiscoveryRun> findTop10ByOrderByStartedAtDesc();
}
