package ai.careerpilot.api.dto;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.domain.WorkflowExecution;
import ai.careerpilot.service.profile.JsonLists;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Workflow Registry, Phase 4 — request/response shapes for {@code WorkflowController}. */
public final class WorkflowRegistryDtos {

    private WorkflowRegistryDtos() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public record WorkflowDefinitionRequest(
            @NotBlank String workflowId, @NotBlank String name, String description,
            @NotBlank String version, @NotBlank String workflowType,
            Map<String, Object> agentConfiguration, List<String> requiredCapabilities, List<String> requiredTools
    ) {
        public WorkflowDefinition toEntity() {
            return WorkflowDefinition.builder()
                    .workflowId(workflowId).name(name).description(description)
                    .version(version).workflowType(workflowType)
                    .agentConfigurationJson(toJson(agentConfiguration))
                    .requiredCapabilitiesJson(JsonLists.toJson(requiredCapabilities))
                    .requiredToolsJson(JsonLists.toJson(requiredTools))
                    .status("ACTIVE")
                    .build();
        }
    }

    public record WorkflowDefinitionResponse(
            UUID id, String workflowId, String name, String description, String version, String workflowType,
            Map<String, Object> agentConfiguration, List<String> requiredCapabilities, List<String> requiredTools,
            String status, Instant createdAt, Instant updatedAt
    ) {
        public static WorkflowDefinitionResponse from(WorkflowDefinition d) {
            return new WorkflowDefinitionResponse(d.getId(), d.getWorkflowId(), d.getName(), d.getDescription(),
                    d.getVersion(), d.getWorkflowType(), toMap(d.getAgentConfigurationJson()),
                    JsonLists.toList(d.getRequiredCapabilitiesJson()), JsonLists.toList(d.getRequiredToolsJson()),
                    d.getStatus(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record ExecuteWorkflowRequest(UUID missionId) {}

    public record WorkflowExecutionResponse(
            UUID id, String workflowId, UUID missionId, String status, String notes,
            Instant startedAt, Instant completedAt, Instant createdAt
    ) {
        public static WorkflowExecutionResponse from(WorkflowExecution e, String workflowId) {
            return new WorkflowExecutionResponse(e.getId(), workflowId, e.getMissionId(), e.getStatus(),
                    e.getNotes(), e.getStartedAt(), e.getCompletedAt(), e.getCreatedAt());
        }
    }
}
