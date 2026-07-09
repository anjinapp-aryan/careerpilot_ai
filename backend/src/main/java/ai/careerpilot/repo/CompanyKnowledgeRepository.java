package ai.careerpilot.repo;

import ai.careerpilot.domain.CompanyKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyKnowledgeRepository extends JpaRepository<CompanyKnowledge, UUID> {
    Optional<CompanyKnowledge> findByUserIdAndNormalizedName(UUID userId, String normalizedName);
    List<CompanyKnowledge> findByUserIdOrderByUpdatedAtDesc(UUID userId);
    List<CompanyKnowledge> findTop50ByUserIdAndNormalizedNameContainingOrderByUpdatedAtDesc(UUID userId, String fragment);
    long countByUserId(UUID userId);
}
