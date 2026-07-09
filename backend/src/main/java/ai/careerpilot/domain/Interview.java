package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.4 — one interview round for an application. Types mirror the lifecycle interview stages
 * (Recruiter/Technical/System Design/Manager/HR/Final/Offer). Append-only; feedback and per-round
 * events live in {@link InterviewFeedback} / {@link InterviewTimeline}.
 */
@Entity
@Table(name = "interview")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Interview {

    public static final String TYPE_RECRUITER = "RECRUITER";
    public static final String TYPE_TECHNICAL = "TECHNICAL";
    public static final String TYPE_SYSTEM_DESIGN = "SYSTEM_DESIGN";
    public static final String TYPE_MANAGER = "MANAGER";
    public static final String TYPE_HR = "HR";
    public static final String TYPE_FINAL = "FINAL";
    public static final String TYPE_OFFER = "OFFER";

    public static final String RESULT_SCHEDULED = "SCHEDULED";
    public static final String RESULT_PASSED = "PASSED";
    public static final String RESULT_FAILED = "FAILED";
    public static final String RESULT_PENDING = "PENDING";
    public static final String RESULT_CANCELLED = "CANCELLED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "interview_type", nullable = false) private String interviewType;
    private String interviewer;
    @Column(name = "duration_minutes") private Integer durationMinutes;
    private String result;
    @Column(name = "scheduled_at") private Instant scheduledAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
