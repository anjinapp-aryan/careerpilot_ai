package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.16 — one generated question/answer pair for an {@link ApplicationSubmissionSession},
 * stored for traceability and approval-screen display. {@code sourceRefs} records which STAR
 * story id / package field the answer drew from (grounded — never fabricated), as JSON text.
 */
@Entity
@Table(name = "application_submission_answer")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationSubmissionAnswer {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "session_id", nullable = false) private UUID sessionId;
    @Column(name = "question_text", nullable = false, columnDefinition = "text") private String questionText;
    @Column(name = "question_category", nullable = false) private String questionCategory;
    @Column(name = "answer_text", columnDefinition = "text") private String answerText;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "source_refs", columnDefinition = "jsonb")
    private String sourceRefs;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
