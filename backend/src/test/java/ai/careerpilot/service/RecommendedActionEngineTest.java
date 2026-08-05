package ai.careerpilot.service;

import ai.careerpilot.mission.MissionOrchestratorService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11B — {@link RecommendedActionEngine} is pure/deterministic: same context in, same
 * priority-ordered actions out, no repository access, no LLM call.
 */
class RecommendedActionEngineTest {

    private final RecommendedActionEngine engine = new RecommendedActionEngine();

    private CareerContextService.CareerContext emptyContext() {
        return new CareerContextService.CareerContext(null, List.of(), null, null, null, List.of(), null, List.of());
    }

    @Test
    void nullContext_returnsEmptyList() {
        assertThat(engine.derive(null)).isEmpty();
    }

    @Test
    void allSectionsAbsent_returnsEmptyList() {
        assertThat(engine.derive(emptyContext())).isEmpty();
    }

    @Test
    void waitingManualSubmission_isHighestPriority() {
        var applications = new CareerContextService.ApplicationsSummary(3, Map.of("APPLIED", 3L), 2);
        var ctx = new CareerContextService.CareerContext(null, List.of(), null, applications, null, List.of(), null, List.of());

        List<RecommendedActionEngine.RecommendedAction> actions = engine.derive(ctx);

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).category()).isEqualTo("Applications");
        assertThat(actions.get(0).title()).contains("2 application(s)");
    }

    @Test
    void pastInterview_doesNotTriggerUpcomingAction() {
        var interviews = new CareerContextService.InterviewSummary(1, 0, 0, "TECHNICAL",
                Instant.now().minus(3, ChronoUnit.DAYS));
        var ctx = new CareerContextService.CareerContext(null, List.of(), null, null, interviews, List.of(), null, List.of());

        assertThat(engine.derive(ctx)).isEmpty();
    }

    @Test
    void futureInterview_triggersUpcomingAction() {
        var interviews = new CareerContextService.InterviewSummary(1, 0, 0, "TECHNICAL",
                Instant.now().plus(1, ChronoUnit.DAYS));
        var ctx = new CareerContextService.CareerContext(null, List.of(), null, null, interviews, List.of(), null, List.of());

        List<RecommendedActionEngine.RecommendedAction> actions = engine.derive(ctx);
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).category()).isEqualTo("Interview");
    }

    @Test
    void missionRecommendations_surfaceAsActions() {
        var decision = new MissionOrchestratorService.Decision("SKILL_ANALYSIS_V1", "incomplete skill actions");
        var mission = new CareerContextService.MissionSummary("Principal Engineer", "PRINCIPAL", "ACTIVE", 24,
                List.of(decision));
        var ctx = new CareerContextService.CareerContext(mission, List.of(), null, null, null, List.of(), null, List.of());

        List<RecommendedActionEngine.RecommendedAction> actions = engine.derive(ctx);
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).category()).isEqualTo("Mission");
        assertThat(actions.get(0).reason()).isEqualTo("incomplete skill actions");
    }

    @Test
    void everythingPresent_isRankedByPriorityLadder() {
        var applications = new CareerContextService.ApplicationsSummary(5, Map.of("REJECTED", 2L), 1);
        var interviews = new CareerContextService.InterviewSummary(1, 0, 0, "TECHNICAL",
                Instant.now().plus(2, ChronoUnit.DAYS));
        var workflow = new CareerContextService.WorkflowSummary("t1", "FAILED", 0, 1, 1);
        var decision = new MissionOrchestratorService.Decision("JOB_DISCOVERY_V1", "apply for open roles");
        var mission = new CareerContextService.MissionSummary("Staff Engineer", "STAFF", "ACTIVE", 12, List.of(decision));
        var ctx = new CareerContextService.CareerContext(mission, List.of(), workflow, applications, interviews,
                List.of(), null, List.of());

        List<RecommendedActionEngine.RecommendedAction> actions = engine.derive(ctx);

        assertThat(actions).extracting(RecommendedActionEngine.RecommendedAction::category)
                .containsExactly("Applications", "Interview", "Workflow", "Mission", "Workflow", "Applications");
        assertThat(actions).isSortedAccordingTo(
                java.util.Comparator.comparingInt(RecommendedActionEngine.RecommendedAction::priority));
    }

    @Test
    void rejectedApplications_withoutOtherSignals_isOnlyAction() {
        var applications = new CareerContextService.ApplicationsSummary(4, Map.of("REJECTED", 3L), 0);
        var ctx = new CareerContextService.CareerContext(null, List.of(), null, applications, null, List.of(), null, List.of());

        List<RecommendedActionEngine.RecommendedAction> actions = engine.derive(ctx);
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).title()).contains("3 rejected application(s)");
    }
}
