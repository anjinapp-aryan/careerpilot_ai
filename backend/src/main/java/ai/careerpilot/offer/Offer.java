package ai.careerpilot.offer;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Gap B — Offer Intelligence & Salary Negotiation (V61, additive, ships dark behind
 * {@code offer.intelligence.enabled}). Either a manually-entered offer or a snapshot captured
 * from the LangGraph {@code salary_intelligence} agent's output on a workflow run.
 */
@Entity
@Table(name = "offers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Offer {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id") private UUID jobId;

    @Column(name = "company_name") private String companyName;
    @Column(name = "base_salary") private BigDecimal baseSalary;
    @Column(name = "bonus") private BigDecimal bonus;
    @Column(name = "rsu_value") private BigDecimal rsuValue;
    @Column(name = "equity_description", columnDefinition = "text") private String equityDescription;
    @Column(name = "benefits_summary", columnDefinition = "text") private String benefitsSummary;
    @Column(name = "joining_bonus") private BigDecimal joiningBonus;
    @Column(name = "currency") private String currency;

    /** MANUAL | SALARY_INTELLIGENCE_AGENT */
    @Builder.Default
    @Column(name = "source", nullable = false) private String source = "MANUAL";

    @Column(name = "market_p25") private BigDecimal marketP25;
    @Column(name = "market_p50") private BigDecimal marketP50;
    @Column(name = "market_p75") private BigDecimal marketP75;
    @Column(name = "market_p90") private BigDecimal marketP90;

    @Column(name = "negotiation_strategy", columnDefinition = "text") private String negotiationStrategy;
    @Column(name = "leverage_points", columnDefinition = "text") private String leveragePoints;

    /** Dedup key for agent-sourced offers — the LangGraph thread_id that produced this row. */
    @Column(name = "source_thread_id") private String sourceThreadId;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
