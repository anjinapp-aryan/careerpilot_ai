package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id") private UUID orgId;
    @Column(name = "user_id") private UUID userId;
    @Column(name = "actor_email") private String actorEmail;
    @Column(nullable = false) private String action;
    @Column(name = "target_type") private String targetType;
    @Column(name = "target_id") private String targetId;
    private String ip;
    @Column(name = "user_agent") private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
