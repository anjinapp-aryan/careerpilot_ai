package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 13A — one validation run against one real employer page, recorded permanently.
 *
 * <p><b>Append-only.</b> No service updates a row here. A run is an observation of how a page
 * looked at a moment in time; rewriting one would destroy the baseline that
 * {@code SelectorDriftDetector} compares against, which is the entire reason the table exists.
 *
 * <p>Note there is deliberately no {@code @UpdateTimestamp} and no {@code updatedAt} column — their
 * presence would invite exactly the in-place edit this contract forbids.
 */
@Entity
@Table(name = "ats_validation_run")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AtsValidationRun {

    @Id @GeneratedValue
    private UUID id;

    /** Who triggered it. Audit only — never exposed on the unauthenticated diagnostics endpoint. */
    @Column(name = "user_id") private UUID userId;

    @Column(name = "ats_platform", nullable = false) private String atsPlatform;

    /** The exact posting. Same visibility rule as {@link #userId}. */
    @Column(nullable = false, columnDefinition = "text") private String url;

    /**
     * Stable hash of the URL, so the per-posting drift series can be indexed and compared without
     * putting a long, user-identifying string in an index key.
     */
    @Column(name = "url_hash", nullable = false) private String urlHash;

    @Column(nullable = false) private String status;
    @Column(columnDefinition = "text") private String message;

    @Column(name = "confidence_score", nullable = false) private Integer confidenceScore;
    @Column(name = "confidence_band", nullable = false) private String confidenceBand;
    @Column(nullable = false) private Boolean ready;

    @Column(name = "total_controls", nullable = false) private Integer totalControls;
    @Column(name = "fillable_controls", nullable = false) private Integer fillableControls;
    @Column(name = "supported_controls", nullable = false) private Integer supportedControls;
    @Column(name = "unsupported_controls", nullable = false) private Integer unsupportedControls;
    @Column(name = "unknown_controls", nullable = false) private Integer unknownControls;
    @Column(name = "required_controls", nullable = false) private Integer requiredControls;
    @Column(name = "mapped_controls", nullable = false) private Integer mappedControls;
    @Column(name = "missing_required_values", nullable = false) private Integer missingRequiredValues;

    @Column(name = "navigation_ms", nullable = false) private Long navigationMs;
    @Column(name = "discovery_ms", nullable = false) private Long discoveryMs;
    @Column(name = "planning_ms", nullable = false) private Long planningMs;
    @Column(name = "total_ms", nullable = false) private Long totalMs;

    @Column(name = "spa_framework") private String spaFramework;
    @Column(name = "iframe_count", nullable = false) private Integer iframeCount;
    @Column(name = "shadow_root_count", nullable = false) private Integer shadowRootCount;
    @Column(name = "captcha_detected", nullable = false) private Boolean captchaDetected;
    @Column(name = "console_error_count", nullable = false) private Integer consoleErrorCount;

    @Column(name = "screenshot_key", columnDefinition = "text") private String screenshotKey;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
