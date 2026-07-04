package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationLifecycleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationLifecycleEventRepository extends JpaRepository<ApplicationLifecycleEvent, UUID> {

    List<ApplicationLifecycleEvent> findByLifecycleIdOrderByOccurredAtDesc(UUID lifecycleId);
}
