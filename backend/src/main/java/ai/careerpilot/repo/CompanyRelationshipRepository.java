package ai.careerpilot.repo;

import ai.careerpilot.domain.CompanyRelationship;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyRelationshipRepository extends JpaRepository<CompanyRelationship, UUID> {
    List<CompanyRelationship> findByCompanyKnowledgeId(UUID companyKnowledgeId);
    List<CompanyRelationship> findByCompanyKnowledgeIdAndRelationType(UUID companyKnowledgeId, String relationType);
    Optional<CompanyRelationship> findByCompanyKnowledgeIdAndRelationTypeAndTarget(UUID companyKnowledgeId, String relationType, String target);
    List<CompanyRelationship> findByUserId(UUID userId);
    long countByUserId(UUID userId);
}
