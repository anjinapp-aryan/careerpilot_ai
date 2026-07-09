package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.13 — one edge in the Company Knowledge Graph: company → industry / technology / skill /
 * role / location / salary band. {@code weight} counts observations, so an edge strengthens every
 * time the same signal is re-observed from a real artifact. Unique per (company, type, target).
 */
@Entity
@Table(name = "company_relationship")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyRelationship {

    public static final String TYPE_INDUSTRY = "INDUSTRY";
    public static final String TYPE_TECHNOLOGY = "TECHNOLOGY";
    public static final String TYPE_SKILL = "SKILL";
    public static final String TYPE_ROLE = "ROLE";
    public static final String TYPE_LOCATION = "LOCATION";
    public static final String TYPE_SALARY_BAND = "SALARY_BAND";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "company_knowledge_id", nullable = false) private UUID companyKnowledgeId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "relation_type", nullable = false) private String relationType;
    @Column(nullable = false) private String target;
    @Column(nullable = false) private Integer weight;
    @Column private String evidence;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
