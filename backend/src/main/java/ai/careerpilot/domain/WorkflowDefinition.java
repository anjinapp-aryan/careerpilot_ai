package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Workflow Registry, Phase 4 — a declarative catalog entry for a reusable workflow type (e.g.
 * {@code JOB_DISCOVERY_V1}). This is metadata only: registering a definition never executes
 * anything. {@code requiredCapabilities}/{@code requiredTools} intentionally use the same string
 * values as the existing {@code ai.careerpilot.capability.CapabilityType} enum (RESUME_ANALYSIS,
 * JOB_RECOMMENDATION, etc.) as a soft naming convention for a future real integration — this
 * table doesn't FK to that enum today, it's a plain JSONB list.
 */
@Entity
@Table(name = "workflow_definition")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowDefinition {
    @Id @GeneratedValue
    private UUID id;

    /** Business key, e.g. "JOB_DISCOVERY_V1" — distinct from the surrogate {@link #id}. */
    @Column(name = "workflow_id", nullable = false, unique = true) private String workflowId;

    @Column(nullable = false) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(nullable = false) private String version;
    @Column(name = "workflow_type", nullable = false) private String workflowType;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "agent_configuration", columnDefinition = "jsonb")
    private String agentConfigurationJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "required_capabilities", columnDefinition = "jsonb")
    private String requiredCapabilitiesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "required_tools", columnDefinition = "jsonb")
    private String requiredToolsJson;

    @Column(nullable = false, length = 20) @Builder.Default private String status = "ACTIVE";

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
