package ai.careerpilot.workflow.career;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Service tests — parses (or tolerates malformed) career_roadmap/skill_gaps from a WorkflowRun's
 * state blob, upserting into the EXISTING single-row-per-user CareerStrategy via the EXISTING
 * CareerStrategyRepository. The agent-service JSON is not guaranteed structured.
 */
class CareerRoadmapPersistenceServiceTest {

    private CareerStrategyRepository repo;

    @BeforeEach
    void setUp() {
        repo = mock(CareerStrategyRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void doesNothingWhenDisabled() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, false);
        UUID userId = UUID.randomUUID();
        Map<String, Object> state = Map.of("career_roadmap", Map.of("north_star_role", "Staff Engineer"));

        CareerStrategy result = service.captureFromWorkflow(userId, state);

        assertNull(result);
        verifyNoInteractions(repo);
    }

    @Test
    void capturesWellFormedRoadmapAndSkillGaps() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, true);
        UUID userId = UUID.randomUUID();
        when(repo.findByUserId(userId)).thenReturn(Optional.empty());

        Map<String, Object> roadmap = Map.of(
                "north_star_role", "Staff Engineer",
                "horizon_3_months", List.of("Ship project X", "Mentor junior"),
                "horizon_6_months", List.of("Lead a team"),
                "horizon_12_months", List.of("Get promoted"));
        Map<String, Object> state = Map.of(
                "career_roadmap", roadmap,
                "skill_gaps", List.of("System design", "Kubernetes"));

        CareerStrategy result = service.captureFromWorkflow(userId, state);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertTrue(result.getRoadmap3Month().contains("Ship project X"));
        assertTrue(result.getRoadmap6Month().contains("Lead a team"));
        assertTrue(result.getRoadmap12Month().contains("Get promoted"));
        assertNotNull(result.getSkillGapsJson());
        assertTrue(result.getSkillGapsJson().contains("System design"));
        assertNotNull(result.getComputedAt());
        verify(repo).save(any(CareerStrategy.class));
    }

    @Test
    void upsertsExistingStrategyRow() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, true);
        UUID userId = UUID.randomUUID();
        CareerStrategy existing = CareerStrategy.builder().id(UUID.randomUUID()).userId(userId).build();
        when(repo.findByUserId(userId)).thenReturn(Optional.of(existing));

        Map<String, Object> roadmap = Map.of("horizon_3_months", List.of("Do X"));
        Map<String, Object> state = Map.of("career_roadmap", roadmap);

        CareerStrategy result = service.captureFromWorkflow(userId, state);

        assertSame(existing, result);
        assertTrue(result.getRoadmap3Month().contains("Do X"));
    }

    @Test
    void returnsNullWhenCareerRoadmapKeyMissing() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, true);
        CareerStrategy result = service.captureFromWorkflow(UUID.randomUUID(), Map.of("other_key", "value"));
        assertNull(result);
        verify(repo, never()).save(any());
    }

    @Test
    void returnsNullWhenCareerRoadmapIsEmptyMap() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, true);
        CareerStrategy result = service.captureFromWorkflow(UUID.randomUUID(), Map.of("career_roadmap", Map.of()));
        assertNull(result);
    }

    @Test
    void returnsNullWhenCareerRoadmapIsWrongType() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, true);
        Map<String, Object> state = Map.of("career_roadmap", "not-a-map");
        CareerStrategy result = service.captureFromWorkflow(UUID.randomUUID(), state);
        assertNull(result);
    }

    @Test
    void toleratesMalformedHorizonFields() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, true);
        UUID userId = UUID.randomUUID();
        when(repo.findByUserId(userId)).thenReturn(Optional.empty());

        Map<String, Object> roadmap = Map.of("horizon_3_months", "not-a-list");
        Map<String, Object> state = Map.of("career_roadmap", roadmap);

        CareerStrategy result = service.captureFromWorkflow(userId, state);

        assertNotNull(result);
        assertNull(result.getRoadmap3Month());
    }

    @Test
    void returnsNullOnNullState() {
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(repo, true);
        assertNull(service.captureFromWorkflow(UUID.randomUUID(), null));
    }

    @Test
    void neverThrowsWhenRepositorySaveFails() {
        CareerStrategyRepository failingRepo = mock(CareerStrategyRepository.class);
        when(failingRepo.findByUserId(any())).thenReturn(Optional.empty());
        when(failingRepo.save(any())).thenThrow(new RuntimeException("db down"));
        CareerRoadmapPersistenceService service = new CareerRoadmapPersistenceService(failingRepo, true);

        Map<String, Object> state = Map.of("career_roadmap", Map.of("horizon_3_months", List.of("x")));

        assertDoesNotThrow(() -> {
            CareerStrategy result = service.captureFromWorkflow(UUID.randomUUID(), state);
            assertNull(result);
        });
    }
}
