package ai.careerpilot.repo;

import ai.careerpilot.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findFirstByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
