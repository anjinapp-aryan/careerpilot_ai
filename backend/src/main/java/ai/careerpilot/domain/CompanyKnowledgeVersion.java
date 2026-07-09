package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.13 — immutable append-only snapshot of one {@link CompanyKnowledge} revision. History is
 * never rewritten: a rollback creates a NEW version whose sections equal the restored snapshot.
 */
@Entity
@Table(name = "company_knowledge_version")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyKnowledgeVersion {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "company_knowledge_id", nullable = false) private UUID companyKnowledgeId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private Integer version;
    @Column(columnDefinition = "text") private String knowledge;        // full section-map snapshot
    @Column(name = "changed_sections", columnDefinition = "text") private String changedSections;
    @Column private String source;                                      // KnowledgeSource name

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
