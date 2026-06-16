package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UsageRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false) private UUID orgId;
    @Column(name = "user_id") private UUID userId;
    @Column(nullable = false) private String feature;
    @Column(nullable = false) private int units;
    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6) private BigDecimal costUsd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
