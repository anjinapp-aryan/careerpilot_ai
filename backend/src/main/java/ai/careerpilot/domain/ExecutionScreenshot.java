package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Gap D — one captured screenshot of a filled (but not yet submitted) guest-apply form, evidence
 * for the mandatory human "approve this specific filled form" gate. Referenced by an {@link
 * ApprovalQueueEntry} of type {@link ApprovalQueueEntry#TYPE_FORM_SCREENSHOT} via {@code
 * screenshot_id}; {@code approval_queue_entry_id} here is the back-reference, set once that entry
 * is enqueued. {@code storageKey} is an S3/MinIO object key (see {@code S3StorageService}), never a
 * public URL — the image is only ever served through an authenticated backend endpoint.
 */
@Entity
@Table(name = "execution_screenshot")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionScreenshot {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "execution_id", nullable = false) private UUID executionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "storage_key", nullable = false) private String storageKey;
    @Column(name = "approval_queue_entry_id") private UUID approvalQueueEntryId;

    @CreationTimestamp @Column(name = "captured_at", updatable = false) private Instant capturedAt;
}
