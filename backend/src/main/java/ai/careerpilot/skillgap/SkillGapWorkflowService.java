package ai.careerpilot.skillgap;

import ai.careerpilot.api.dto.SkillGapDtos.SkillGapAnalysisResponse;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.SkillGapAnalysis;
import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.mission.MissionNotFoundException;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.SkillGapAnalysisRepository;
import ai.careerpilot.service.profile.JsonLists;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 10 — Skill Gap Intelligence Workflow. This service is the Java Control Plane's entire
 * responsibility for this workflow: input validation, workflow definition lookup, invoking the
 * Python AI Execution Plane, persistence, result retrieval, and history tracking. It contains no
 * AI reasoning of its own — every business judgment (skill classification, roadmap, readiness
 * score) happens in {@code agent-service/app/skillgap/}; this class only marshals data across the
 * boundary and stores the opaque result.
 */
@Service
public class SkillGapWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(SkillGapWorkflowService.class);
    private static final String WORKFLOW_TYPE = "SKILL_GAP_INTELLIGENCE";

    private final CareerMissionRepository missions;
    private final WorkflowRegistryService registry;
    private final SkillGapAgentServiceClient agentClient;
    private final SkillGapAnalysisRepository analyses;
    private final ObjectMapper mapper;

    @Value("${skillgap.workflow.enabled:false}")
    private boolean enabled;

    public SkillGapWorkflowService(CareerMissionRepository missions, WorkflowRegistryService registry,
                                    SkillGapAgentServiceClient agentClient, SkillGapAnalysisRepository analyses,
                                    ObjectMapper mapper) {
        this.missions = missions;
        this.registry = registry;
        this.agentClient = agentClient;
        this.analyses = analyses;
        this.mapper = mapper;
    }

    /**
     * Triggers a new Skill Gap analysis run for the mission. Synchronous (no async worker/event
     * chain, unlike Phase 2D's pipeline stages) — a Skill Gap run is a single bounded request to
     * the AI Execution Plane, not a multi-stage background job, so the simpler synchronous shape
     * fits; a future phase can add an async variant without changing this one's contract.
     */
    @Transactional
    public SkillGapAnalysisResponse trigger(UUID userId, UUID missionId) {
        if (!enabled) {
            throw new IllegalStateException("Skill Gap Intelligence workflow is not enabled");
        }

        CareerMission mission = missions.findByIdAndUserId(missionId, userId)
                .orElseThrow(() -> new MissionNotFoundException(missionId));

        WorkflowDefinition definition = registry.latestForType(WORKFLOW_TYPE)
                .orElseThrow(() -> new IllegalStateException(
                        "No active " + WORKFLOW_TYPE + " workflow definition is registered"));

        String executionId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();

        SkillGapAnalysis analysis = analyses.save(SkillGapAnalysis.builder()
                .missionId(missionId).userId(userId).workflowId(definition.getWorkflowId())
                .executionId(executionId).correlationId(correlationId).status("RUNNING")
                .build());

        try {
            SkillGapAgentResponse response = agentClient.startRun(buildPayload(mission, definition, executionId, correlationId));
            if (response == null) {
                analysis.setStatus("FAILED");
                analysis.setErrorMessage("Skill Gap workflow returned no response");
            } else if ("error".equalsIgnoreCase(response.status())) {
                analysis.setStatus("FAILED");
                analysis.setErrorMessage(response.errors() == null || response.errors().isEmpty()
                        ? "Skill Gap workflow reported an error" : String.join("; ", response.errors()));
                analysis.setResultJson(writeJson(response));
            } else {
                analysis.setStatus("SUCCEEDED");
                analysis.setResultJson(writeJson(response));
            }
        } catch (Exception e) {
            log.error("skill_gap_trigger_failed: missionId={}, executionId={}", missionId, executionId, e);
            analysis.setStatus("FAILED");
            analysis.setErrorMessage(e.getMessage());
        }

        analysis.setCompletedAt(Instant.now());
        analysis = analyses.save(analysis);
        return toResponse(analysis);
    }

    public SkillGapAnalysisResponse latest(UUID userId, UUID missionId) {
        ensureMissionOwnership(userId, missionId);
        return analyses.findFirstByMissionIdAndUserIdOrderByCreatedAtDesc(missionId, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new SkillGapAnalysisNotFoundException(missionId));
    }

    public List<SkillGapAnalysisResponse> history(UUID userId, UUID missionId) {
        ensureMissionOwnership(userId, missionId);
        return analyses.findByMissionIdAndUserIdOrderByCreatedAtDesc(missionId, userId)
                .stream().map(this::toResponse).toList();
    }

    private void ensureMissionOwnership(UUID userId, UUID missionId) {
        missions.findByIdAndUserId(missionId, userId).orElseThrow(() -> new MissionNotFoundException(missionId));
    }

    private Map<String, Object> buildPayload(CareerMission mission, WorkflowDefinition definition,
                                              String executionId, String correlationId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("mission_id", mission.getId().toString());
        payload.put("user_id", mission.getUserId().toString());
        payload.put("workflow_id", definition.getWorkflowId());
        payload.put("execution_id", executionId);
        payload.put("correlation_id", correlationId);
        payload.put("mission_statement", mission.getMissionStatement());
        payload.put("target_role", mission.getTargetRole());
        payload.put("target_level", mission.getTargetLevel());
        payload.put("target_countries", JsonLists.toList(mission.getTargetCountriesJson()));
        payload.put("timeline_months", mission.getTimelineMonths());
        payload.put("current_skills", JsonLists.toList(mission.getCurrentSkillsJson()));
        payload.put("skills_to_acquire", JsonLists.toList(mission.getSkillsToAcquireJson()));
        return payload;
    }

    private SkillGapAnalysisResponse toResponse(SkillGapAnalysis entity) {
        return new SkillGapAnalysisResponse(entity.getId(), entity.getMissionId(), entity.getWorkflowId(),
                entity.getExecutionId(), entity.getCorrelationId(), entity.getStatus(),
                parseResult(entity.getResultJson()), entity.getErrorMessage(), entity.getCreatedAt(),
                entity.getCompletedAt());
    }

    private Map<String, Object> parseResult(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("skill_gap_result_parse_failed: {}", e.toString());
            return Map.of();
        }
    }

    private String writeJson(SkillGapAgentResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("skill_gap_result_serialize_failed: {}", e.toString());
            return null;
        }
    }
}
