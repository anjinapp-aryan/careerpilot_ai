package ai.careerpilot.repo;

import ai.careerpilot.domain.CompanyKnowledgeVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyKnowledgeVersionRepository extends JpaRepository<CompanyKnowledgeVersion, UUID> {
    List<CompanyKnowledgeVersion> findByCompanyKnowledgeIdOrderByVersionDesc(UUID companyKnowledgeId);
    Optional<CompanyKnowledgeVersion> findByCompanyKnowledgeIdAndVersion(UUID companyKnowledgeId, Integer version);
    long countByUserId(UUID userId);
}
