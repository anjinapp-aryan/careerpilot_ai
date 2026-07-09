package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Phase 5 — one row per scheduled/manual daily discovery pipeline run. */
@Entity
@Table(name = "daily_discovery_run")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyDiscoveryRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "correlation_id") private UUID correlationId;
    @Builder.Default
    @Column(nullable = false) private String status = STATUS_RUNNING;
    @Column(name = "jobs_fetched") private Integer jobsFetched;
    @Column(name = "jobs_normalized") private Integer jobsNormalized;
    @Column(name = "jobs_deduped") private Integer jobsDeduped;
    @Column(name = "users_processed") private Integer usersProcessed;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Builder.Default
    @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();
    @Column(name = "finished_at") private Instant finishedAt;
}
