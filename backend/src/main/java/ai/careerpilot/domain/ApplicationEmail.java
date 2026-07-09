package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.3 — a captured application-related email, deterministically classified by
 * {@code EmailClassifier}. INERT in this build: there is no mailbox integration and nothing fetches
 * email — rows are only ever created through the (dark) manual ingest path. Append-only.
 */
@Entity
@Table(name = "application_email")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationEmail {

    public static final String CATEGORY_ACKNOWLEDGEMENT = "ACKNOWLEDGEMENT";
    public static final String CATEGORY_ASSESSMENT = "ASSESSMENT";
    public static final String CATEGORY_INTERVIEW = "INTERVIEW";
    public static final String CATEGORY_OFFER = "OFFER";
    public static final String CATEGORY_REJECTION = "REJECTION";
    public static final String CATEGORY_WITHDRAWAL = "WITHDRAWAL";
    public static final String CATEGORY_UNKNOWN = "UNKNOWN";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id") private UUID jobId;
    private String subject;
    @Column(columnDefinition = "text") private String body;
    @Column(nullable = false) private String category;
    private Double confidence;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
