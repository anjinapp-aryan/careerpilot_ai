package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase D — one logical employer question, deduplicated across employers and ATS platforms.
 *
 * <p>{@code normalizedText} is unique and is the identity of the question: three employers asking
 * "current country of residence" in three phrasings produce one row, seen three times. {@code
 * employer} and {@code atsPlatform} record where it was last observed and are <b>never</b> consulted
 * when matching — keying on them would defeat the reuse this table exists to enable.
 */
@Entity
@Table(name = "employer_question")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployerQuestion {

    @Id @GeneratedValue private UUID id;

    @Column(name = "original_text", nullable = false, columnDefinition = "text")
    private String originalText;

    @Column(name = "normalized_text", nullable = false, columnDefinition = "text")
    private String normalizedText;

    @Column(name = "canonical_field", nullable = false) private String canonicalField;
    @Column(name = "question_category", nullable = false) private String questionCategory;
    @Column(name = "question_type", nullable = false) private String questionType;
    @Column(name = "required", nullable = false) private boolean required;
    @Column(name = "confidence", nullable = false) private int confidence;

    @Column(name = "employer") private String employer;
    @Column(name = "ats_platform") private String atsPlatform;

    @Column(name = "times_seen", nullable = false) private int timesSeen;
    @Column(name = "first_seen_at", nullable = false) private Instant firstSeenAt;
    @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
}
