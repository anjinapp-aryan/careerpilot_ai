package ai.careerpilot.runtime;

/**
 * LangGraph Workflow Runtime — the clean abstraction between this Java Control Plane runtime and
 * the Python AI Execution Plane that actually runs a workflow. This interface is a client
 * contract, not an execution contract: implementations transport a request across the language
 * boundary and return whatever came back — they never execute a graph, a node, or an agent
 * in-process in Java. {@link LangGraphWorkflowExecutor} is the only implementation today
 * (invoking the existing Python LangGraph agent-service over HTTP); a future implementation (a
 * different transport/protocol to the same or another AI Execution Plane, or a test double) can
 * be swapped in via {@link WorkflowRuntimeConfiguration} without any caller of {@link
 * WorkflowRuntime} changing. Swapping the implementation never means Java hosting a graph engine
 * of its own — that ownership stays in Python regardless of which {@code WorkflowExecutor} is
 * wired.
 *
 * @throws WorkflowTimeoutException if the underlying call exceeds its configured timeout
 * @throws WorkflowCancelledException if the execution was cancelled before or during invocation
 * @throws WorkflowExecutionException for any other execution failure
 */
public interface WorkflowExecutor {

    WorkflowExecutorOutcome execute(WorkflowExecutionContext context);
}
