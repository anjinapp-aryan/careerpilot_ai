package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Phase 6.2 — learned success rate for one (user, dimension, key), e.g. (user, COMPANY, "JP Morgan"). */
@Entity
@Table(name = "success_pattern")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SuccessPattern {

    public static final String DIM_COMPANY = "COMPANY";
    public static final String DIM_ROLE = "ROLE";
    public static final String DIM_SKILL = "SKILL";
    public static final String DIM_RESUME = "RESUME";
    public static final String DIM_LOCATION = "LOCATION";
    public static final String DIM_INDUSTRY = "INDUSTRY";
    public static final String DIM_SALARY = "SALARY";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private String dimension;
    @Column(name = "dimension_key", nullable = false) private String dimensionKey;
    @Builder.Default @Column(nullable = false) private int applications = 0;
    @Builder.Default @Column(nullable = false) private int interviews = 0;
    @Builder.Default @Column(nullable = false) private int offers = 0;
    @Column(name = "success_rate") private BigDecimal successRate;
    @Column(name = "computed_at") private Instant computedAt;
}
