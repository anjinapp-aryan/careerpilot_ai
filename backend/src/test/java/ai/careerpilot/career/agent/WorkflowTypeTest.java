package ai.careerpilot.career.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 7A — pure translation table, no dispatch logic; pinned so the mapping doesn't silently drift. */
class WorkflowTypeTest {

    @Test
    void everyAgentTaskTypeMapsToASensibleWorkflowType() {
        assertThat(WorkflowType.forAgentTask(AgentTaskType.JOB_DISCOVERY)).isEqualTo(WorkflowType.JOB_DISCOVERY);
        assertThat(WorkflowType.forAgentTask(AgentTaskType.RESUME_OPTIMIZATION)).isEqualTo(WorkflowType.RESUME);
        assertThat(WorkflowType.forAgentTask(AgentTaskType.INTERVIEW_PLANNING)).isEqualTo(WorkflowType.INTERVIEW);
        assertThat(WorkflowType.forAgentTask(AgentTaskType.LEARNING_ROADMAP)).isEqualTo(WorkflowType.LEARNING);
        assertThat(WorkflowType.forAgentTask(AgentTaskType.SKILL_GAP_DETECTION)).isEqualTo(WorkflowType.LEARNING);
        assertThat(WorkflowType.forAgentTask(AgentTaskType.SALARY_TREND_MONITORING)).isEqualTo(WorkflowType.SALARY);
    }

    @Test
    void nullTaskMapsToNull() {
        assertThat(WorkflowType.forAgentTask(null)).isNull();
    }
}
