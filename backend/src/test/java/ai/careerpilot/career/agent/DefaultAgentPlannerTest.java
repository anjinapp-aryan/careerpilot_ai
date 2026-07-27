package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerAlert;
import ai.careerpilot.career.monitor.CareerAlertSeverity;
import ai.careerpilot.career.monitor.CareerAlertType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentPlannerTest {

    private final DefaultAgentPlanner planner = new DefaultAgentPlanner(5);

    private CareerAlert alert(UUID userId, CareerAlertType type) {
        return CareerAlert.of(userId, type, CareerAlertSeverity.HIGH, "msg", Map.of());
    }

    @Test
    void noSignalsProducesEmptyPlan() {
        UUID userId = UUID.randomUUID();
        AgentObservation observation = AgentObservation.empty(userId);

        AgentExecutionPlan plan = planner.plan(observation);

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.reason()).contains("no signals observed");
    }

    @Test
    void jobMatchSignalMapsToJobDiscoveryTask() {
        UUID userId = UUID.randomUUID();
        AgentObservation observation = new AgentObservation(userId, java.time.Instant.now(),
                List.of(alert(userId, CareerAlertType.JOB_MATCH)), "test");

        AgentExecutionPlan plan = planner.plan(observation);

        assertThat(plan.tasks()).containsExactly(AgentTaskType.JOB_DISCOVERY);
    }

    @Test
    void multipleSignalsMapToMultipleDedupedTasks() {
        UUID userId = UUID.randomUUID();
        AgentObservation observation = new AgentObservation(userId, java.time.Instant.now(),
                List.of(alert(userId, CareerAlertType.RESUME_OUTDATED), alert(userId, CareerAlertType.RESUME_OUTDATED),
                        alert(userId, CareerAlertType.INTERVIEW_REMINDER)), "test");

        AgentExecutionPlan plan = planner.plan(observation);

        assertThat(plan.tasks()).containsExactlyInAnyOrder(AgentTaskType.RESUME_OPTIMIZATION, AgentTaskType.INTERVIEW_PLANNING);
    }

    @Test
    void respectsMaxTasksPerPlan() {
        UUID userId = UUID.randomUUID();
        DefaultAgentPlanner tinyPlanner = new DefaultAgentPlanner(1);
        AgentObservation observation = new AgentObservation(userId, java.time.Instant.now(),
                List.of(alert(userId, CareerAlertType.RESUME_OUTDATED), alert(userId, CareerAlertType.INTERVIEW_REMINDER)), "test");

        AgentExecutionPlan plan = tinyPlanner.plan(observation);

        assertThat(plan.tasks()).hasSize(1);
    }

    @Test
    void applicationTrackingIsNeverPlannedByDefaultMapping() {
        UUID userId = UUID.randomUUID();
        AgentObservation observation = new AgentObservation(userId, java.time.Instant.now(),
                List.of(alert(userId, CareerAlertType.JOB_MATCH), alert(userId, CareerAlertType.RESUME_OUTDATED),
                        alert(userId, CareerAlertType.SALARY_BELOW_MARKET), alert(userId, CareerAlertType.PROMOTION_READY),
                        alert(userId, CareerAlertType.INTERVIEW_REMINDER), alert(userId, CareerAlertType.LEARNING_SUGGESTION),
                        alert(userId, CareerAlertType.MISSING_CERTIFICATION)), "test");

        AgentExecutionPlan plan = planner.plan(observation);

        assertThat(plan.tasks()).doesNotContain(AgentTaskType.APPLICATION_TRACKING);
    }
}
