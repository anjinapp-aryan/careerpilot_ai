package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase D — one candidate's answer to one logical question, reusable across every employer that
 * asks it.
 *
 * <p><b>{@code approved} is the gate, not {@code confidence}.</b> A draft is stored so a human can
 * read it; until they approve it, no automation path may use it. The approver and timestamp are
 * recorded because "who decided this was true, and when" is the question an audit actually asks.
 */
@Entity
@Table(name = "employer_answer")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployerAnswer {

    @Id @GeneratedValue private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "question_id", nullable = false) private UUID questionId;

    @Column(name = "answer_text", columnDefinition = "text") private String answerText;

    @Column(name = "confidence", nullable = false) private String confidence;

    @Column(name = "approved", nullable = false) private boolean approved;
    @Column(name = "approved_by") private UUID approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;

    @Column(name = "source") private String source;

    @Column(name = "usage_count", nullable = false) private int usageCount;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
