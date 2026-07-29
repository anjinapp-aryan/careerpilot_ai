package ai.careerpilot.career.agent;

import java.util.Map;

/**
 * Phase 7A — LangGraph dispatch preparation only. A richer future taxonomy than {@link
 * AgentTaskType} (this agent's own 8 task categories), matching the workflow families a future
 * LangGraph-backed dispatch layer would route through Spring AI/MCP/the AI Gateway. Introduced
 * per the phase's explicit "prepare clean orchestration, do not implement these workflows"
 * scope: this enum plus {@link #forAgentTask} is a pure, uncalled translation table — no
 * {@link AgentTaskExecutor} implementation shipped today reads it. A future LangGraph-dispatching
 * executor is the intended consumer.
 */
public enum WorkflowType {
    RESUME,
    JOB_DISCOVERY,
    ATS,
    INTERVIEW,
    LEARNING,
    OFFER,
    VISA,
    RELOCATION,
    COMPANY,
    SALARY;

    private static final Map<AgentTaskType, WorkflowType> FROM_AGENT_TASK = Map.of(
            AgentTaskType.JOB_DISCOVERY, JOB_DISCOVERY,
            AgentTaskType.RESUME_OPTIMIZATION, RESUME,
            AgentTaskType.APPLICATION_TRACKING, JOB_DISCOVERY,
            AgentTaskType.INTERVIEW_PLANNING, INTERVIEW,
            AgentTaskType.CAREER_ROADMAP_UPDATE, RELOCATION,
            AgentTaskType.LEARNING_ROADMAP, LEARNING,
            AgentTaskType.SKILL_GAP_DETECTION, LEARNING,
            AgentTaskType.SALARY_TREND_MONITORING, SALARY);

    /** The {@link WorkflowType} family a future LangGraph dispatcher would route this task to, or {@code null} if unmapped. */
    public static WorkflowType forAgentTask(AgentTaskType task) {
        return task == null ? null : FROM_AGENT_TASK.get(task);
    }
}
