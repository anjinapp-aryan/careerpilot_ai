package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Phase 6.6 — learned per-user ranking score for one (dimension, key), e.g. best companies/skills/industries. */
@Entity
@Table(name = "career_learning")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CareerLearning {

    public static final String DIM_COMPANY = "COMPANY";
    public static final String DIM_SKILL = "SKILL";
    public static final String DIM_INDUSTRY = "INDUSTRY";
    public static final String DIM_LOCATION = "LOCATION";
    public static final String DIM_SALARY = "SALARY";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private String dimension;
    @Column(name = "dimension_key", nullable = false) private String dimensionKey;
    private BigDecimal score;
    @Column(name = "sample_size") private Integer sampleSize;
    @Column(name = "computed_at") private Instant computedAt;
}
