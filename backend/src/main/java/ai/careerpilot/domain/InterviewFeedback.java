package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 3A.4 — append-only feedback captured for an {@link Interview} round. */
@Entity
@Table(name = "interview_feedback")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InterviewFeedback {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "interview_id", nullable = false) private UUID interviewId;
    @Column(columnDefinition = "text") private String feedback;
    private Integer rating;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
