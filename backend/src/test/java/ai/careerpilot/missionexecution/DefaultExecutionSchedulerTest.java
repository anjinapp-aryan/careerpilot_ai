package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExecutionSchedulerTest {

    private final DefaultExecutionScheduler scheduler = new DefaultExecutionScheduler();

    @Test
    void matchesThePhaseSpecsWorkedExampleOrderingForAllTenCoreTypes() {
        List<WorkflowType> all10 = List.of(
                WorkflowType.RESUME, WorkflowType.LINKEDIN, WorkflowType.PORTFOLIO, WorkflowType.LEARNING,
                WorkflowType.JOB_DISCOVERY, WorkflowType.ATS, WorkflowType.INTERVIEW,
                WorkflowType.OFFER_EVALUATION, WorkflowType.VISA, WorkflowType.RELOCATION);

        Map<WorkflowType, Integer> weeks = scheduler.schedule(all10);

        assertThat(weeks.get(WorkflowType.RESUME)).isEqualTo(1);
        assertThat(weeks.get(WorkflowType.LINKEDIN)).isEqualTo(2);
        assertThat(weeks.get(WorkflowType.PORTFOLIO)).isEqualTo(3);
        assertThat(weeks.get(WorkflowType.LEARNING)).isEqualTo(4);
        assertThat(weeks.get(WorkflowType.JOB_DISCOVERY)).isEqualTo(5);
        assertThat(weeks.get(WorkflowType.ATS)).isEqualTo(6);
        assertThat(weeks.get(WorkflowType.INTERVIEW)).isEqualTo(7);
        assertThat(weeks.get(WorkflowType.OFFER_EVALUATION)).isEqualTo(8);
        assertThat(weeks.get(WorkflowType.VISA)).isEqualTo(9);
        assertThat(weeks.get(WorkflowType.RELOCATION)).isEqualTo(10);
    }

    @Test
    void onlyRequestedTypesAreScheduledAndOrderIsPreservedWithGaps() {
        Map<WorkflowType, Integer> weeks = scheduler.schedule(List.of(WorkflowType.INTERVIEW, WorkflowType.RESUME));

        assertThat(weeks.get(WorkflowType.RESUME)).isEqualTo(1);
        assertThat(weeks.get(WorkflowType.INTERVIEW)).isEqualTo(2);
    }

    @Test
    void typesOutsideTheNaturalSequenceAreAppendedInRequestedOrder() {
        Map<WorkflowType, Integer> weeks = scheduler.schedule(
                List.of(WorkflowType.RESUME, WorkflowType.SALARY, WorkflowType.NETWORKING));

        assertThat(weeks.get(WorkflowType.RESUME)).isEqualTo(1);
        assertThat(weeks.get(WorkflowType.SALARY)).isEqualTo(2);
        assertThat(weeks.get(WorkflowType.NETWORKING)).isEqualTo(3);
    }
}
