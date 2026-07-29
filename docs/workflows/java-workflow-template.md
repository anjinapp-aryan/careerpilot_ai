# Java Workflow Template

Copy-pasteable skeleton for a new CareerPilot AI workflow's Java side. Replace `<Workflow>`/
`<workflow>`/`<WORKFLOW_TYPE>` throughout. See `docs/architecture/WORKFLOW_STANDARD.md` for the
rationale behind every piece — this file is the "just show me the code" companion, not the spec.

**Reminder**: this template calls `WorkflowRuntime.execute(...)` directly — it does **not** create
a new HTTP client, unlike Skill Gap Intelligence's historical shape. See the Standard's
"Deviation from the reference implementation" section before diverging from this template.

---

## 1. Migration — `V<next>__<workflow>_analysis.sql`

```sql
INSERT INTO workflow_definition (workflow_id, name, description, version, workflow_type, required_capabilities)
VALUES (
    '<WORKFLOW_TYPE>_V1',
    '<Human-readable name>',
    '<One-sentence description of what this workflow answers>',
    'v1',
    '<WORKFLOW_TYPE>',
    '[]'
);

CREATE TABLE <workflow>_analysis (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id      UUID NOT NULL REFERENCES career_mission(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL,
    workflow_id     VARCHAR(100) NOT NULL,
    execution_id    VARCHAR(100) NOT NULL,
    correlation_id  VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    result          JSONB,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_<workflow>_analysis_execution_id UNIQUE (execution_id)
);

CREATE INDEX idx_<workflow>_analysis_mission_created ON <workflow>_analysis (mission_id, created_at DESC);
CREATE INDEX idx_<workflow>_analysis_user ON <workflow>_analysis (user_id);
```

## 2. Entity — `domain/<Workflow>Analysis.java`

```java
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
@Table(name = "<workflow>_analysis")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class <Workflow>Analysis {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "mission_id", nullable = false) private UUID missionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "workflow_id", nullable = false) private String workflowId;
    @Column(name = "execution_id", nullable = false, unique = true) private String executionId;
    @Column(name = "correlation_id") private String correlationId;

    @Column(nullable = false, length = 20) @Builder.Default
    private String status = "QUEUED";

    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;
}
```

## 3. Repository — `repo/<Workflow>AnalysisRepository.java`

```java
package ai.careerpilot.repo;

import ai.careerpilot.domain.<Workflow>Analysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface <Workflow>AnalysisRepository extends JpaRepository<<Workflow>Analysis, UUID> {
    List<<Workflow>Analysis> findByMissionIdAndUserIdOrderByCreatedAtDesc(UUID missionId, UUID userId);
    Optional<<Workflow>Analysis> findFirstByMissionIdAndUserIdOrderByCreatedAtDesc(UUID missionId, UUID userId);
}
```

## 4. Not-found exception — `<workflow>/<Workflow>AnalysisNotFoundException.java`

```java
package ai.careerpilot.<workflow>;

import java.util.NoSuchElementException;
import java.util.UUID;

public class <Workflow>AnalysisNotFoundException extends NoSuchElementException {
    public <Workflow>AnalysisNotFoundException(UUID missionId) {
        super("No <Workflow> analysis found for mission: " + missionId);
    }
}
```

## 5. DTOs — `api/dto/<Workflow>Dtos.java`

```java
package ai.careerpilot.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class <Workflow>Dtos {
    private <Workflow>Dtos() {}

    public record <Workflow>AnalysisResponse(
            UUID id, UUID missionId, String workflowId, String executionId, String correlationId,
            String status, Map<String, Object> result, String errorMessage,
            Instant createdAt, Instant completedAt) {
    }
}
```

## 6. Service — `<workflow>/<Workflow>WorkflowService.java`

```java
package ai.careerpilot.<workflow>;

import ai.careerpilot.api.dto.<Workflow>Dtos.<Workflow>AnalysisResponse;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.<Workflow>Analysis;
import ai.careerpilot.mission.MissionNotFoundException;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.<Workflow>AnalysisRepository;
import ai.careerpilot.runtime.WorkflowExecutionRequest;
import ai.careerpilot.runtime.WorkflowExecutionResult;
import ai.careerpilot.runtime.WorkflowRuntime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class <Workflow>WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(<Workflow>WorkflowService.class);
    private static final String WORKFLOW_TYPE = "<WORKFLOW_TYPE>";

    private final CareerMissionRepository missions;
    private final ObjectProvider<WorkflowRuntime> workflowRuntime;
    private final <Workflow>AnalysisRepository analyses;
    private final ObjectMapper mapper;

    @Value("${<workflow>.workflow.enabled:false}")
    private boolean enabled;

    public <Workflow>WorkflowService(CareerMissionRepository missions, ObjectProvider<WorkflowRuntime> workflowRuntime,
                                      <Workflow>AnalysisRepository analyses, ObjectMapper mapper) {
        this.missions = missions;
        this.workflowRuntime = workflowRuntime;
        this.analyses = analyses;
        this.mapper = mapper;
    }

    @Transactional
    public <Workflow>AnalysisResponse trigger(UUID userId, UUID missionId) {
        if (!enabled) {
            throw new IllegalStateException("<Workflow> workflow is not enabled");
        }
        WorkflowRuntime runtime = workflowRuntime.getIfAvailable();
        if (runtime == null) {
            throw new IllegalStateException("runtime.enabled must also be true for <workflow>.workflow.enabled to work");
        }

        CareerMission mission = missions.findByIdAndUserId(missionId, userId)
                .orElseThrow(() -> new MissionNotFoundException(missionId));

        String executionId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        WorkflowExecutionRequest request = new WorkflowExecutionRequest(
                missionId, userId, WORKFLOW_TYPE, null, buildInputs(mission), correlationId);

        <Workflow>Analysis analysis = analyses.save(<Workflow>Analysis.builder()
                .missionId(missionId).userId(userId).workflowId(WORKFLOW_TYPE + "_V1")
                .executionId(executionId).correlationId(correlationId).status("RUNNING")
                .build());

        WorkflowExecutionResult result = runtime.execute(request);
        analysis.setStatus(result.successful() ? "SUCCEEDED" : "FAILED");
        analysis.setResultJson(writeJson(result.outputPayload()));
        analysis.setErrorMessage(result.errors().isEmpty() ? null : String.join("; ", result.errors()));
        analysis.setCompletedAt(Instant.now());
        analysis = analyses.save(analysis);

        log.info("<workflow>_trigger_completed: missionId={}, executionId={}, status={}",
                missionId, executionId, analysis.getStatus());
        return toResponse(analysis);
    }

    public <Workflow>AnalysisResponse latest(UUID userId, UUID missionId) {
        ensureMissionOwnership(userId, missionId);
        return analyses.findFirstByMissionIdAndUserIdOrderByCreatedAtDesc(missionId, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new <Workflow>AnalysisNotFoundException(missionId));
    }

    public List<<Workflow>AnalysisResponse> history(UUID userId, UUID missionId) {
        ensureMissionOwnership(userId, missionId);
        return analyses.findByMissionIdAndUserIdOrderByCreatedAtDesc(missionId, userId)
                .stream().map(this::toResponse).toList();
    }

    private void ensureMissionOwnership(UUID userId, UUID missionId) {
        missions.findByIdAndUserId(missionId, userId).orElseThrow(() -> new MissionNotFoundException(missionId));
    }

    /** Business data marshalling only — never AI reasoning. Read from CareerMission (and any
     * other already-persisted entity this workflow legitimately needs), never fabricate a field. */
    private Map<String, Object> buildInputs(CareerMission mission) {
        Map<String, Object> inputs = new HashMap<>();
        // inputs.put("targetRole", mission.getTargetRole()); // example
        return inputs;
    }

    private <Workflow>AnalysisResponse toResponse(<Workflow>Analysis entity) {
        return new <Workflow>AnalysisResponse(entity.getId(), entity.getMissionId(), entity.getWorkflowId(),
                entity.getExecutionId(), entity.getCorrelationId(), entity.getStatus(),
                parseResult(entity.getResultJson()), entity.getErrorMessage(), entity.getCreatedAt(),
                entity.getCompletedAt());
    }

    private Map<String, Object> parseResult(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("<workflow>_result_parse_failed: {}", e.toString());
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("<workflow>_result_serialize_failed: {}", e.toString());
            return null;
        }
    }
}
```

## 7. Controller — `api/<Workflow>Controller.java`

```java
package ai.careerpilot.api;

import ai.careerpilot.api.dto.<Workflow>Dtos.<Workflow>AnalysisResponse;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.<workflow>.<Workflow>WorkflowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/<workflow>")
public class <Workflow>Controller {

    private final <Workflow>WorkflowService service;

    public <Workflow>Controller(<Workflow>WorkflowService service) {
        this.service = service;
    }

    @PostMapping("/{missionId}/run")
    public <Workflow>AnalysisResponse run(AuthenticatedUser user, @PathVariable UUID missionId) {
        return service.trigger(user.userId(), missionId);
    }

    @GetMapping("/{missionId}/latest")
    public <Workflow>AnalysisResponse latest(AuthenticatedUser user, @PathVariable UUID missionId) {
        return service.latest(user.userId(), missionId);
    }

    @GetMapping("/{missionId}/history")
    public List<<Workflow>AnalysisResponse> history(AuthenticatedUser user, @PathVariable UUID missionId) {
        return service.history(user.userId(), missionId);
    }
}
```

## 8. `package-info.java` — `<workflow>/package-info.java`

```java
/**
 * Phase N — <Workflow> Intelligence Workflow. Answers "<the business question>" by invoking a
 * dedicated LangGraph graph (agent-service/app/<workflow>/) through the generalized {@link
 * ai.careerpilot.runtime.WorkflowRuntime} (Phase 9/10A) — no bespoke HTTP client, per the
 * Workflow Development Standard (docs/architecture/WORKFLOW_STANDARD.md).
 *
 * <h2>Java Control Plane responsibilities (this package)</h2>
 * Workflow registration (one seed row, V<next>__<workflow>_analysis.sql), input validation
 * (mission ownership + <workflow>.workflow.enabled), building the transport payload from {@link
 * ai.careerpilot.domain.CareerMission} (data marshalling, not AI reasoning), persistence/history
 * ({@link ai.careerpilot.domain.<Workflow>Analysis}), the REST API ({@link
 * ai.careerpilot.api.<Workflow>Controller}).
 *
 * <h2>Python AI Execution Plane responsibilities (agent-service/app/<workflow>/)</h2>
 * The LangGraph graph, its state, all business agents, prompt orchestration, agent execution.
 * Java never reimplements any of this.
 *
 * Gated by {@code <workflow>.workflow.enabled} (default {@code false}) AND {@code runtime.enabled}
 * (the shared platform flag, Phase 9).
 */
package ai.careerpilot.<workflow>;
```

## 9. Wiring checklist

- [ ] Migration applied (`V<next>__<workflow>_analysis.sql`)
- [ ] `ai.careerpilot.<workflow>.<Workflow>WorkflowService` created
- [ ] `ai.careerpilot.api.<Workflow>Controller` created
- [ ] `<Workflow>.workflow.enabled` flag added (inline `@Value`, no `application.yml` entry needed)
- [ ] `mvn test` full suite green
