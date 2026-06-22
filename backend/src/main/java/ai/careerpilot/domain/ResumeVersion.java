package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable, AI-optimized version of a {@link Resume}. Created by the
 * RESUME_OPTIMIZATION workflow on approval; the original resume is never overwritten.
 */
@Entity
@Table(name = "resume_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeVersion {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "resume_id", nullable = false) private UUID resumeId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "org_id", nullable = false) private UUID orgId;

    @Column(name = "version_number", nullable = false) private Integer versionNumber;
    @Column(name = "optimization_mode") private String optimizationMode;
    @Column(name = "ats_before") private Integer atsBefore;
    @Column(name = "ats_after") private Integer atsAfter;
    @Column(name = "provider_used") private String providerUsed;
    @Column(name = "workflow_thread_id") private String workflowThreadId;
    @Column(name = "s3_key") private String s3Key;
    @Column(name = "content_type") private String contentType;

    @Column(name = "optimized_text", columnDefinition = "text") private String optimizedText;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
