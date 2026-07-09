package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 6.1 — one audit row per learning-pipeline stage execution; backs diagnostics latency/health. */
@Entity
@Table(name = "learning_metrics")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LearningMetricsLog {

    public static final String STAGE_EVENT_CAPTURE = "EVENT_CAPTURE";
    public static final String STAGE_SUCCESS_PATTERN = "SUCCESS_PATTERN";
    public static final String STAGE_FAILURE_PATTERN = "FAILURE_PATTERN";
    public static final String STAGE_RECOMMENDATION_LEARNING = "RECOMMENDATION_LEARNING";
    public static final String STAGE_RESUME_LEARNING = "RESUME_LEARNING";
    public static final String STAGE_CAREER_LEARNING = "CAREER_LEARNING";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "learning_event_id") private UUID learningEventId;
    @Column(nullable = false) private String stage;
    @Column(nullable = false) private String status;
    @Column(name = "latency_ms") private Long latencyMs;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
