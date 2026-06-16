package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "applications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Application {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "org_id", nullable = false) private UUID orgId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_id") private UUID resumeId;

    @Column(nullable = false) private String status; // SAVED|APPLIED|INTERVIEWING|OFFER|REJECTED|WITHDRAWN
    @Column(name = "match_score") private Integer matchScore;
    @Column(name = "ats_score") private Integer atsScore;
    @Column(name = "next_action", columnDefinition = "text") private String nextAction;
    @Column(name = "next_action_at") private Instant nextActionAt;
    @Column(columnDefinition = "text") private String notes;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
