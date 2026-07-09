package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.3 — the structured fields extracted from an {@link ApplicationEmail} (best-effort,
 * deterministic). Append-only, one per processed email.
 */
@Entity
@Table(name = "email_extraction")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailExtraction {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "email_id", nullable = false) private UUID emailId;
    private String company;
    @Column(name = "job_title") private String jobTitle;
    @Column(name = "interview_type") private String interviewType;
    @Column(name = "extracted_date") private String extractedDate;
    private String salary;
    private Double confidence;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
