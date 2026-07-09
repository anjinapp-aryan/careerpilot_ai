package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.5 — an append-only per-user analytics metric snapshot (one row per metric per recompute).
 * Kept deliberately separate from Phase 2E's {@code execution_analytics} — different bounded context,
 * different metric vocabulary (career outcomes vs execution latencies).
 */
@Entity
@Table(name = "application_analytics")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationAnalytics {

    public static final String METRIC_APPLICATION_COUNT = "APPLICATION_COUNT";
    public static final String METRIC_INTERVIEW_RATE = "INTERVIEW_RATE";
    public static final String METRIC_OFFER_RATE = "OFFER_RATE";
    public static final String METRIC_REJECTION_RATE = "REJECTION_RATE";
    public static final String METRIC_COUNTRY_SUCCESS = "COUNTRY_SUCCESS";
    public static final String METRIC_COMPANY_SUCCESS = "COMPANY_SUCCESS";
    public static final String METRIC_TECHNOLOGY_SUCCESS = "TECHNOLOGY_SUCCESS";
    public static final String METRIC_SALARY_SUCCESS = "SALARY_SUCCESS";
    public static final String METRIC_DOMAIN_SUCCESS = "DOMAIN_SUCCESS";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private String metric;
    @Column(precision = 12, scale = 4) private BigDecimal value;
    @Column(name = "dimension_key") private String dimensionKey;
    @Column(name = "window_start") private Instant windowStart;

    @CreationTimestamp @Column(name = "computed_at", updatable = false) private Instant computedAt;
}
