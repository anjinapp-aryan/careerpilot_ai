package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, UUID> {

    List<ApplicationStatusHistory> findByLifecycleIdOrderByChangedAtDesc(UUID lifecycleId);
}
