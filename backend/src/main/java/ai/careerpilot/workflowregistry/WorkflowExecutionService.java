package ai.careerpilot.workflowregistry;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.domain.WorkflowExecution;
import ai.careerpilot.repo.WorkflowExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Workflow Registry, Phase 4 — {@code execute} deliberately always produces a {@code DEFERRED}
 * {@link WorkflowExecution} row today, same honesty discipline as {@code
 * DeferredAgentTaskExecutor} (Phase 11.6): every workflow type in the catalog already has a real
 * engine somewhere in this codebase (the LangGraph pipeline via {@code WorkflowService} for the
 * standard resume/job/interview/strategy flow), and per CLAUDE.md's own rule — "do not expose the
 * agent service to the frontend, funnel it through WorkflowService" — this registry does not
 * bypass that by invoking the agent-service directly. A future phase can make this real by
 * calling the appropriate existing service per {@code workflowType}; today it only records intent.
 */
@Service
public class WorkflowExecutionService {

    private final WorkflowExecutionRepository executions;

    public WorkflowExecutionService(WorkflowExecutionRepository executions) {
        this.executions = executions;
    }

    @Transactional
    public WorkflowExecution execute(WorkflowDefinition definition, UUID userId, UUID missionId) {
        WorkflowExecution execution = WorkflowExecution.builder()
                .workflowDefinitionId(definition.getId())
                .userId(userId)
                .missionId(missionId)
                .status("DEFERRED")
                .notes("Workflow execution is not yet wired for workflow_type=" + definition.getWorkflowType()
                        + ". Existing pipelines (resume/job/interview/strategy) remain reachable only through "
                        + "WorkflowService, never directly from this registry.")
                .startedAt(Instant.now())
                .build();
        return executions.save(execution);
    }

    public List<WorkflowExecution> history(UUID userId) {
        return executions.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
