package ai.careerpilot.runtime;

import java.util.Map;
import java.util.UUID;

/**
 * LangGraph Workflow Runtime — the generic <b>integration payload</b> handed to a {@link
 * WorkflowExecutor}. Deliberately opaque: {@link #inputs()}/{@link #outputs()}/{@link #context()}
 * are plain {@code Map<String, Object>} because this runtime has no knowledge of what a resume,
 * job, interview, or skill workflow actually needs.
 *
 * <h2>This is NOT LangGraph state</h2>
 * {@code WorkflowState} is a Java Control Plane transport record that crosses the language
 * boundary as an HTTP request/response body — it is not, and must never become, a substitute for
 * Python's {@code CareerState} (the actual LangGraph graph state, defined in {@code
 * agent-service/app/state.py} and owned exclusively by the Python AI Execution Plane). Concretely,
 * this record:
 * <ul>
 *   <li>must never contain graph topology — no node list, no edge list, no conditional-routing
 *       rules; which node runs next is a decision LangGraph makes in Python, never Java;</li>
 *   <li>must never contain node execution state — no "current node," no per-node status, no
 *       checkpoint/thread position; that is LangGraph's {@code PostgresSaver} checkpoint, not
 *       this record;</li>
 *   <li>must never replace or mirror {@code CareerState} — Java does not need to understand
 *       {@code CareerState}'s shape to transport a request to it and a result payload back.</li>
 * </ul>
 * If a future phase needs richer workflow-specific input/output shapes, they must remain
 * business-payload data (what to send, what came back) — never graph-execution metadata. Adding
 * graph-topology or node-state fields to this record would move LangGraph ownership into Java and
 * must not be done; see {@code ai.careerpilot.runtime}'s package-info for the full ownership
 * statement.
 */
public record WorkflowState(UUID missionId, UUID userId, String workflowId, String executionId,
                             Map<String, Object> context, Map<String, Object> inputs,
                             Map<String, Object> outputs, Map<String, Object> metadata) {

    public WorkflowState {
        context = context == null ? Map.of() : Map.copyOf(context);
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Returns a copy of this state with {@link #outputs()} replaced — used once an executor returns. */
    public WorkflowState withOutputs(Map<String, Object> newOutputs) {
        return new WorkflowState(missionId, userId, workflowId, executionId, context, inputs, newOutputs, metadata);
    }
}
