package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Raw, unmodified provider payload captured at fetch time (Phase 2A Job Lake, Step 4). Provider
 * data is never discarded — this is the source of truth the normalized/lake layers derive from.
 *
 * <p>Idempotent on {@code (provider, external_id, checksum)}: an unchanged listing re-ingested on a
 * later run is a no-op (same checksum), while a changed listing produces a new row, preserving
 * history. Part of the SHADOW lake pipeline — written only when {@code JOB_DISCOVERY_ENABLED=true}.
 */
@Entity
@Table(name = "job_raw")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobRaw {

    @Id @GeneratedValue
    private UUID id;

    @Column(nullable = false) private String provider;
    @Column(name = "external_id", nullable = false) private String externalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    /** SHA-256 of the raw payload; together with provider+externalId forms the idempotency key. */
    @Column(nullable = false) private String checksum;

    /** The discovery run that captured this row (correlates raw → audit). */
    @Column(name = "run_id") private UUID runId;

    @CreationTimestamp @Column(name = "fetched_at", updatable = false) private Instant fetchedAt;
}
