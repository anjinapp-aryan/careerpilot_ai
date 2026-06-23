package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One row per provider per discovery run — observability for the ingest pipeline. */
@Entity
@Table(name = "job_fetch_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobFetchAudit {
    @Id @GeneratedValue
    private UUID id;

    @Column(nullable = false) private String provider;
    @CreationTimestamp @Column(name = "started_at", updatable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "jobs_fetched", nullable = false) private int jobsFetched;
    @Column(name = "jobs_persisted", nullable = false) private int jobsPersisted;

    /** RUNNING | SUCCESS | FAILED */
    @Column(nullable = false) private String status;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
}
