package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowRun {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "org_id", nullable = false) private UUID orgId;
    @Column(name = "thread_id", nullable = false, unique = true) private String threadId;
    @Column(nullable = false) private String status;          // RUNNING | INTERRUPTED | COMPLETED | ERROR
    @Column(name = "target_role") private String targetRole;
    @Column(name = "target_seniority") private String targetSeniority;
    @Column(name = "resume_score") private Integer resumeScore;
    @Column(name = "job_match_score") private Integer jobMatchScore;
    @Column(name = "ats_score") private Integer atsScore;
    @Column(name = "interview_readiness_score") private Integer interviewReadinessScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String state;

    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
