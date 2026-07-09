package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.13 — append-only entry in a company's journey for one user: first discovered, first
 * recommendation, application, interview, offer, rejection, knowledge/learning updates.
 */
@Entity
@Table(name = "company_timeline_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyTimelineEvent {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "company_knowledge_id", nullable = false) private UUID companyKnowledgeId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "ref_id") private UUID refId;
    @Column(columnDefinition = "text") private String detail;

    @CreationTimestamp @Column(name = "occurred_at", updatable = false) private Instant occurredAt;
}
