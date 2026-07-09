package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2E.4 — a MANDATORY human approval checkpoint for one application-execution request. HIGH
 * RISK. In v1 there is no auto-approve: a row leaves {@code PENDING} only via the human approve/reject
 * endpoints, and only the approve path publishes {@code ApprovalGrantedEvent}. Double-guarded so a
 * terminal row can never be re-decided (mirrors the workflow human-approval guard).
 */
@Entity
@Table(name = "approval_queue")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApprovalQueueEntry {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_package_id", nullable = false) private UUID applicationPackageId;
    @Column(name = "safety_verdict", nullable = false) private String safetyVerdict;
    @Column(nullable = false) private String status;
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decision_note", columnDefinition = "text") private String decisionNote;

    @CreationTimestamp @Column(name = "requested_at", updatable = false) private Instant requestedAt;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "expires_at") private Instant expiresAt;
}
