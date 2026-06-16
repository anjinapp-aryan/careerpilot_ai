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
@Table(name = "resumes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Resume {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "org_id", nullable = false) private UUID orgId;
    @Column(nullable = false) private String filename;
    @Column(name = "s3_key", nullable = false) private String s3Key;
    @Column(name = "content_type") private String contentType;
    @Column(name = "size_bytes") private Long sizeBytes;

    @Column(name = "parsed_text", columnDefinition = "text") private String parsedText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_profile", columnDefinition = "jsonb")
    private String candidateProfileJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_skills", columnDefinition = "jsonb")
    private String extractedSkillsJson;

    @Column(name = "resume_score") private Integer resumeScore;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
