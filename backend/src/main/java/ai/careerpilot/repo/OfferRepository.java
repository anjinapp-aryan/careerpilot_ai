package ai.careerpilot.repo;

import ai.careerpilot.offer.Offer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfferRepository extends JpaRepository<Offer, UUID> {
    List<Offer> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<Offer> findByUserIdAndIdIn(UUID userId, List<UUID> ids);
    Optional<Offer> findByUserIdAndSourceThreadId(UUID userId, String sourceThreadId);
}
