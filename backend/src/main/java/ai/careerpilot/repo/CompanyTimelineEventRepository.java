package ai.careerpilot.repo;

import ai.careerpilot.domain.CompanyTimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompanyTimelineEventRepository extends JpaRepository<CompanyTimelineEvent, UUID> {
    List<CompanyTimelineEvent> findByCompanyKnowledgeIdOrderByOccurredAtDesc(UUID companyKnowledgeId);
    List<CompanyTimelineEvent> findByCompanyKnowledgeIdAndEventTypeOrderByOccurredAtDesc(UUID companyKnowledgeId, String eventType);
    long countByCompanyKnowledgeIdAndEventType(UUID companyKnowledgeId, String eventType);
    long countByUserId(UUID userId);
}
