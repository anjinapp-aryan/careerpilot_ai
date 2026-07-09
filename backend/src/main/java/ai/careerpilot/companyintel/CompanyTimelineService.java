package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyTimelineEvent;
import ai.careerpilot.repo.CompanyTimelineEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.13 — append-only company journey per user: first discovered, recommended, applied,
 * interviewed, offer/rejection, knowledge updates. Gated by {@code company.timeline.enabled}
 * (default off); recording is a silent no-op when dark so callers never need their own guard.
 */
@Service
public class CompanyTimelineService {

    private final CompanyTimelineEventRepository events;
    private final boolean enabled;

    public CompanyTimelineService(CompanyTimelineEventRepository events,
                                  @Value("${company.timeline.enabled:false}") boolean enabled) {
        this.events = events;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void record(CompanyKnowledge company, CompanyTimelineEventType type, UUID refId, String detail) {
        if (!enabled || company == null || company.getId() == null) return;
        events.save(CompanyTimelineEvent.builder()
                .companyKnowledgeId(company.getId())
                .userId(company.getUserId())
                .eventType(type.name())
                .refId(refId)
                .detail(detail)
                .build());
    }

    public List<CompanyTimelineEvent> timeline(UUID companyKnowledgeId) {
        return events.findByCompanyKnowledgeIdOrderByOccurredAtDesc(companyKnowledgeId);
    }

    public long count(UUID companyKnowledgeId, CompanyTimelineEventType type) {
        return events.countByCompanyKnowledgeIdAndEventType(companyKnowledgeId, type.name());
    }
}
