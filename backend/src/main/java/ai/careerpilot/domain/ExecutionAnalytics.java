package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2E.8 — an append-only per-user metric rollup snapshot (submitted count, success/interview/
 * offer rate, provider/browser latency, application duration). Computed from execution + tracking
 * events; never mutated in place.
 */
@Entity
@Table(name = "execution_analytics")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionAnalytics {

    public static final String METRIC_APPLICATIONS_SUBMITTED = "applications_submitted";
    public static final String METRIC_SUCCESS_RATE = "success_rate";
    public static final String METRIC_FAILURE_RATE = "failure_rate";
    public static final String METRIC_ATS_SUCCESS = "ats_success";
    public static final String METRIC_INTERVIEW_RATE = "interview_rate";
    public static final String METRIC_OFFER_RATE = "offer_rate";
    public static final String METRIC_PROVIDER_LATENCY_MS = "provider_latency_ms";
    public static final String METRIC_BROWSER_LATENCY_MS = "browser_latency_ms";
    public static final String METRIC_APPLICATION_DURATION_MS = "application_duration_ms";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private String metric;
    @Column(nullable = false) private BigDecimal value;
    @Column(name = "window_start") private Instant windowStart;

    @CreationTimestamp @Column(name = "computed_at", updatable = false) private Instant computedAt;
}
