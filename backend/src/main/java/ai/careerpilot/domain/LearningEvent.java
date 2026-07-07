package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 6.1 — append-only capture of one outcome-bearing signal observed by the learning engine. */
@Entity
@Table(name = "learning_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LearningEvent {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "correlation_id") private UUID correlationId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id") private UUID jobId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "resume_version") private String resumeVersion;
    @Column(name = "workflow_id") private String workflowId;
    private String company;
    private String country;
    @Column(name = "role_family") private String roleFamily;
    @Column(columnDefinition = "text") private String skills;
    private String industry;
    @Column(name = "salary_band") private String salaryBand;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
