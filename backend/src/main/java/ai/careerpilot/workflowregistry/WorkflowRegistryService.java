package ai.careerpilot.workflowregistry;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.repo.WorkflowDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Workflow Registry, Phase 4 — a declarative catalog, not an execution engine. Registering a
 * definition is pure metadata bookkeeping; it never invokes anything (same "registering never
 * means executing" discipline as {@code McpRegistry}, Phase 10.1).
 */
@Service
public class WorkflowRegistryService {

    private final WorkflowDefinitionRepository definitions;

    public WorkflowRegistryService(WorkflowDefinitionRepository definitions) {
        this.definitions = definitions;
    }

    public List<WorkflowDefinition> listActive() {
        return definitions.findByStatusOrderByWorkflowIdAsc("ACTIVE");
    }

    public WorkflowDefinition get(String workflowId) {
        return definitions.findByWorkflowId(workflowId)
                .orElseThrow(() -> new NoSuchElementException("Workflow definition not found: " + workflowId));
    }

    /**
     * Phase 8 — the latest ACTIVE definition for a {@code workflow_type} (e.g. {@code
     * RESUME_OPTIMIZATION}), or empty if none is registered. Non-throwing, unlike {@link #get},
     * because {@code ai.careerpilot.workflowplanner.WorkflowSelector} treats "no definition yet
     * for this type" as a normal, plannable-to-fail state, not a hard error on a known id.
     */
    public Optional<WorkflowDefinition> latestForType(String workflowType) {
        return definitions.findByWorkflowTypeAndStatusOrderByVersionDesc(workflowType, "ACTIVE").stream().findFirst();
    }

    @Transactional
    public WorkflowDefinition register(WorkflowDefinition definition) {
        if (definitions.existsByWorkflowId(definition.getWorkflowId())) {
            throw new IllegalStateException("Workflow already registered: " + definition.getWorkflowId());
        }
        return definitions.save(definition);
    }
}
