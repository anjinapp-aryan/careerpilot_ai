package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 8.2 — one row per analyze/re-analyze attempt for a specific (user, resume) pair. Tracks
 * only the lifecycle of the attempt itself; the actual extraction result lives on
 * {@link CandidateProfile} (one row per user) exactly as before this feature. NOT_ANALYZED,
 * OUTDATED, and PARTIAL are never persisted here — they're derived at read time in
 * {@code ResumeIntelligenceCenterService} by comparing the latest run against the current profile.
 */
@Entity
@Table(name = "resume_analysis_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeAnalysisRun {

    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_ANALYZED = "ANALYZED";
    public static final String STATUS_FAILED = "FAILED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "resume_id", nullable = false) private UUID resumeId;

    @Column(name = "status", nullable = false) private String status;

    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "profile_version_id") private UUID profileVersionId;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
