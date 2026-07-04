package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.3 — append-only audit trail for email intelligence actions (classification/extraction).
 */
@Entity
@Table(name = "email_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailAudit {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "email_id") private UUID emailId;
    @Column(nullable = false) private String action;
    @Column(columnDefinition = "text") private String detail;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
