package ai.careerpilot.workflowplanner;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.service.profile.JsonLists;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 8 — the only {@link WorkflowPlanFactory}. Pure assembly: derives {@code requiredInputs}/
 * {@code expectedOutputs} by flattening step-level lists, {@code approvalRequired} from whether
 * any step requires it, {@code capabilityType} from the registry definition's {@code
 * required_capabilities} (best-effort — the first entry that matches a real {@link
 * CapabilityType}), {@code requiredMcpCapabilities} from the registry definition's {@code
 * required_tools}, and {@link WorkflowExecutionStrategy} from a simple, documented heuristic. No
 * I/O, no AI calls.
 */
public class DefaultWorkflowPlanFactory implements WorkflowPlanFactory {

    @Override
    public WorkflowPlan build(WorkflowPlanRequest request, WorkflowDefinition definition,
                               WorkflowStepGrouping grouping, WorkflowEstimate estimate) {
        List<WorkflowStep> allSteps = concat(grouping.sequentialSteps(), grouping.parallelSteps());

        List<String> requiredInputs = flatten(allSteps, WorkflowStep::requiredInputs);
        List<String> expectedOutputs = flatten(allSteps, WorkflowStep::expectedOutputs);
        boolean approvalRequired = allSteps.stream().anyMatch(WorkflowStep::approvalRequired);

        List<String> requiredCapabilities = JsonLists.toList(definition.getRequiredCapabilitiesJson());
        CapabilityType capabilityType = requiredCapabilities.stream()
                .map(DefaultWorkflowPlanFactory::toCapabilityType)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        List<String> requiredMcpCapabilities = JsonLists.toList(definition.getRequiredToolsJson());

        WorkflowExecutionStrategy strategy = !grouping.parallelSteps().isEmpty() ? WorkflowExecutionStrategy.PARALLEL
                : approvalRequired ? WorkflowExecutionStrategy.HUMAN_APPROVAL
                : WorkflowExecutionStrategy.SEQUENTIAL;

        String graphId = request.workflowType().name() + "_GRAPH_V1";

        return new WorkflowPlan(
                java.util.UUID.randomUUID(),
                request.workflowType(),
                definition.getVersion(),
                request.priority(),
                request.missionId(),
                request.strategyId(),
                capabilityType,
                estimate.complexity(),
                estimate.estimatedDuration(),
                grouping.sequentialSteps(),
                grouping.parallelSteps(),
                requiredInputs,
                expectedOutputs,
                approvalRequired,
                RetryStrategy.standard(),
                FallbackStrategy.escalateToHuman(),
                requiredMcpCapabilities,
                graphId,
                "start",
                "end",
                "auto",
                Map.of("definitionWorkflowId", definition.getWorkflowId(), "definitionVersion", definition.getVersion()),
                estimate,
                strategy,
                Instant.now());
    }

    private static List<WorkflowStep> concat(List<WorkflowStep> a, List<WorkflowStep> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    private static List<String> flatten(List<WorkflowStep> steps, java.util.function.Function<WorkflowStep, List<String>> f) {
        Set<String> out = new LinkedHashSet<>();
        for (WorkflowStep s : steps) {
            out.addAll(f.apply(s));
        }
        return List.copyOf(out);
    }

    private static CapabilityType toCapabilityType(String name) {
        try {
            return CapabilityType.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }
}
