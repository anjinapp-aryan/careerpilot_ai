package ai.careerpilot.skillgap;

import ai.careerpilot.api.dto.SkillGapDtos.SkillGapAnalysisResponse;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.SkillGapAnalysis;
import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.mission.MissionNotFoundException;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.SkillGapAnalysisRepository;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillGapWorkflowServiceTest {

    private final CareerMissionRepository missions = mock(CareerMissionRepository.class);
    private final WorkflowRegistryService registry = mock(WorkflowRegistryService.class);
    private final SkillGapAgentServiceClient agentClient = mock(SkillGapAgentServiceClient.class);
    private final SkillGapAnalysisRepository analyses = mock(SkillGapAnalysisRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private SkillGapWorkflowService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID missionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SkillGapWorkflowService(missions, registry, agentClient, analyses, mapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(analyses.save(any(SkillGapAnalysis.class))).thenAnswer(inv -> {
            SkillGapAnalysis a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
    }

    private CareerMission mission() {
        return CareerMission.builder().id(missionId).userId(userId).targetRole("Senior Java Architect")
                .targetCountriesJson("[\"Germany\"]").currentSkillsJson("[\"Java\",\"Spring Boot\"]")
                .timelineMonths(12).build();
    }

    private WorkflowDefinition definition() {
        return WorkflowDefinition.builder().workflowId("SKILL_GAP_INTELLIGENCE_V1")
                .workflowType("SKILL_GAP_INTELLIGENCE").version("v1").status("ACTIVE").build();
    }

    @Test
    void triggerThrowsWhenFlagDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThatThrownBy(() -> service.trigger(userId, missionId)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(missions, agentClient);
    }

    @Test
    void triggerThrowsMissionNotFoundWhenMissionDoesNotBelongToUser() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trigger(userId, missionId)).isInstanceOf(MissionNotFoundException.class);
        verifyNoInteractions(agentClient);
    }

    @Test
    void triggerThrowsWhenNoWorkflowDefinitionRegistered() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(registry.latestForType("SKILL_GAP_INTELLIGENCE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.trigger(userId, missionId)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(agentClient);
    }

    @Test
    void triggerSucceedsAndPersistsTheResult() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(registry.latestForType("SKILL_GAP_INTELLIGENCE")).thenReturn(Optional.of(definition()));
        SkillGapAgentResponse response = new SkillGapAgentResponse(
                missionId.toString(), "SKILL_GAP_INTELLIGENCE_V1", "exec-1", "completed",
                78, 0.9, List.of(Map.of("skill", "Kubernetes")), List.of(), List.of(),
                List.of(), 6, 0.78, List.of("8 years experience"), List.of("no k8s"), List.of("learn k8s"), List.of());
        when(agentClient.startRun(any())).thenReturn(response);

        SkillGapAnalysisResponse result = service.trigger(userId, missionId);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.result()).containsEntry("readinessScore", 78);
        verify(analyses, times(2)).save(any(SkillGapAnalysis.class));
    }

    @Test
    void triggerMarksFailedWhenAgentReportsError() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(registry.latestForType("SKILL_GAP_INTELLIGENCE")).thenReturn(Optional.of(definition()));
        SkillGapAgentResponse response = new SkillGapAgentResponse(
                missionId.toString(), "SKILL_GAP_INTELLIGENCE_V1", "exec-1", "error",
                0, 0.0, List.of(), List.of(), List.of(), List.of(), 0, 0.0,
                List.of(), List.of(), List.of(), List.of("market_intelligence: all providers failed"));
        when(agentClient.startRun(any())).thenReturn(response);

        SkillGapAnalysisResponse result = service.trigger(userId, missionId);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("all providers failed");
    }

    @Test
    void triggerNeverPropagatesAnAgentClientException() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(registry.latestForType("SKILL_GAP_INTELLIGENCE")).thenReturn(Optional.of(definition()));
        when(agentClient.startRun(any())).thenThrow(new SkillGapAgentServiceClient.SkillGapAgentServiceException("down", null));

        SkillGapAnalysisResponse result = service.trigger(userId, missionId);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("down");
    }

    @Test
    void latestThrowsNotFoundWhenNoAnalysisExists() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(analyses.findFirstByMissionIdAndUserIdOrderByCreatedAtDesc(missionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latest(userId, missionId)).isInstanceOf(SkillGapAnalysisNotFoundException.class);
    }

    @Test
    void latestReturnsTheMostRecentAnalysis() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        SkillGapAnalysis entity = SkillGapAnalysis.builder().id(UUID.randomUUID()).missionId(missionId)
                .userId(userId).workflowId("SKILL_GAP_INTELLIGENCE_V1").executionId("exec-1").status("SUCCEEDED").build();
        when(analyses.findFirstByMissionIdAndUserIdOrderByCreatedAtDesc(missionId, userId)).thenReturn(Optional.of(entity));

        SkillGapAnalysisResponse result = service.latest(userId, missionId);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void historyThrowsMissionNotFoundForAnUnownedMission() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(userId, missionId)).isInstanceOf(MissionNotFoundException.class);
    }

    @Test
    void historyReturnsAllRunsForTheMission() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        SkillGapAnalysis a = SkillGapAnalysis.builder().id(UUID.randomUUID()).missionId(missionId).userId(userId)
                .workflowId("SKILL_GAP_INTELLIGENCE_V1").executionId("exec-1").status("SUCCEEDED").build();
        SkillGapAnalysis b = SkillGapAnalysis.builder().id(UUID.randomUUID()).missionId(missionId).userId(userId)
                .workflowId("SKILL_GAP_INTELLIGENCE_V1").executionId("exec-2").status("FAILED").build();
        when(analyses.findByMissionIdAndUserIdOrderByCreatedAtDesc(missionId, userId)).thenReturn(List.of(a, b));

        List<SkillGapAnalysisResponse> result = service.history(userId, missionId);

        assertThat(result).hasSize(2);
    }

}
