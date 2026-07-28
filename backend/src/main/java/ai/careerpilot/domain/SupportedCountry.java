package ai.careerpilot.domain;

import ai.careerpilot.jobdiscovery.international.CountryTier;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * International Job Discovery Engine, Phase 1 — one row per country in the Phase-1 relocation
 * program. Config-driven and admin-editable-later: future countries are a data-only insert here,
 * never a code change. Gated end-to-end by {@code career.international.tiering.enabled}.
 */
@Entity
@Table(name = "supported_countries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SupportedCountry {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "country_code", nullable = false, unique = true, length = 2)
    private String countryCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CountryTier tier;

    @Column(nullable = false)
    private Boolean active;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
