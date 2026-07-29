/**
 * LangGraph Workflow Runtime — the bridge between planning (Mission Engine → Strategy Engine →
 * Mission Orchestrator → Workflow Planner → Mission Execution Engine, all pre-existing and
 * unchanged) and execution (the Python AI Execution Plane — LangGraph Runtime → AI Agent
 * Workflows → Capability Layer → AI Providers). This package answers exactly one question — "how
 * do I execute this workflow" — never "what should the workflow do." Planning is already complete
 * by the time a {@link ai.careerpilot.runtime.WorkflowExecutionRequest} reaches {@link
 * ai.careerpilot.runtime.WorkflowRuntime#execute}; this runtime never decides which workflow to
 * run, only how to hand the one it's given across the language boundary.
 *
 * <h2>Phase 10A — generalized from a single-graph client into a reusable dispatch platform</h2>
 * Skill Gap Intelligence (Phase 10) proved this package's Phase 9 shape didn't generalize: {@link
 * ai.careerpilot.runtime.LangGraphWorkflowExecutor} could only reach the main career graph (Python
 * {@code /runs}), and {@link ai.careerpilot.runtime.WorkflowExecutionRequest} required an {@code
 * ai.careerpilot.missionexecution.ExecutionDecision} — which itself required a {@code
 * ai.careerpilot.workflowplanner.WorkflowType} enum value, meaning every new workflow would have
 * needed an enum change. Phase 10A closes that gap, evidence-driven, with zero breaking changes
 * (nothing outside this package called any of it before or after):
 * <ul>
 *   <li>{@link ai.careerpilot.runtime.WorkflowExecutionRequest#workflowId()} (a plain string) is
 *       now the canonical identity — {@link ai.careerpilot.runtime.WorkflowExecutionRequest#executionDecision()}
 *       is optional, present only when a Mission Execution Engine caller wants its policy/priority
 *       carried through. See ADR-007.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowRegistryAdapter#resolve(String)} is the primary
 *       resolution method (the {@code WorkflowType} overload is now a convenience default method,
 *       not the only path). See ADR-007.</li>
 *   <li>{@link ai.careerpilot.runtime.LangGraphWorkflowExecutor} now calls {@link
 *       ai.careerpilot.agent.AgentServiceClient#startWorkflowRun(String, java.util.Map)} — {@code
 *       POST /workflows/{workflowId}/runs}, the new generic Python dispatcher — reaching any
 *       registered workflow, not only the main graph. {@code /runs} and {@code startRun} are
 *       untouched. See ADR-006.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowExecutionResult#metrics()} now includes {@code
 *       missionId} alongside the existing {@code workflowVersion}/{@code executionPolicy}/{@code
 *       correlationId}/{@code retryCount}. See ADR-008.</li>
 * </ul>
 * Full rationale: {@code docs/adr/ADR-006-generic-workflow-dispatch.md}, {@code
 * ADR-007-workflow-registration-strategy.md}, {@code ADR-008-workflow-execution-contract.md}.
 * {@code ai.careerpilot.skillgap} (Phase 10) was **not** modified by this phase — it remains on
 * its own dedicated endpoint/client, proven as a read-only reference against the new generic path
 * (see {@code agent-service/app/skillgap/registration.py}), not migrated onto it.
 *
 * <h2>Architecture freeze — this package is the Java-side half of the language boundary</h2>
 * Per the CareerPilot AI canonical architecture, Java is the <b>Control Plane</b> and Python is
 * the <b>AI Execution Plane</b>. This package is the single point where the Control Plane crosses
 * that boundary; the rest of the canonical diagram (LangGraph Runtime → AI Agent Workflows →
 * Capability Layer → AI Providers) lives entirely in {@code agent-service/} (Python) and is
 * explicitly out of this package's ownership.
 * <table border="1">
 *   <caption>Ownership split</caption>
 *   <tr><th>Java Control Plane owns (this package)</th><th>Python AI Execution Plane owns ({@code agent-service/})</th></tr>
 *   <tr><td>Validating an {@link ai.careerpilot.runtime.WorkflowExecutionRequest}</td>
 *       <td>Deciding what an AI agent does with the request</td></tr>
 *   <tr><td>Resolving Workflow Registry metadata ({@link ai.careerpilot.runtime.WorkflowRegistryAdapter})</td>
 *       <td>The LangGraph {@code StateGraph} — nodes, edges, conditional routing ({@code agent-service/app/graph.py})</td></tr>
 *   <tr><td>Building an opaque transport payload ({@link ai.careerpilot.runtime.WorkflowState})</td>
 *       <td>Graph execution state — {@code CareerState} ({@code agent-service/app/state.py})</td></tr>
 *   <tr><td>Invoking the AI Execution Plane over HTTP ({@link ai.careerpilot.runtime.LangGraphWorkflowExecutor})</td>
 *       <td>Multi-agent orchestration, agent-to-agent handoff, per-node execution</td></tr>
 *   <tr><td>Timing/event bookkeeping for the Java-side call ({@link ai.careerpilot.runtime.ExecutionTrace})</td>
 *       <td>Graph checkpointing ({@code PostgresSaver}), {@code NodeInterrupt} human-approval pausing</td></tr>
 *   <tr><td>Mapping the HTTP response into a {@link ai.careerpilot.runtime.WorkflowExecutionResult}</td>
 *       <td>Calling the Capability Layer / AI Providers to actually reason</td></tr>
 * </table>
 * This package does <b>not</b> own, and must never be extended to own: graph topology, node
 * execution order, conditional-edge logic, agent-to-agent orchestration, or LangGraph checkpoint
 * state. Any of those appearing in a future change here would be a Control-Plane/Execution-Plane
 * boundary violation, not a legitimate extension.
 *
 * <h2>No business logic, no AI reasoning</h2>
 * Nothing here knows how a resume, job, interview, or skill workflow actually works — every
 * input/output map ({@link ai.careerpilot.runtime.WorkflowState#inputs()}/{@code outputs()}/
 * {@code context()}) is opaque {@code Map<String, Object>}, passed through unchanged. Grep confirms
 * no import of {@code ai.careerpilot.ai}, {@code ai.springai}, {@code ai.careerpilot.mcp}, or any
 * resume/job/interview/skill-specific package anywhere under {@code ai.careerpilot.runtime} — and
 * no class named after a LangGraph concept ({@code GraphNode}, {@code ConditionalEdge}, {@code
 * GraphStateMachine}, {@code GraphExecutor}, {@code AgentGraph}) exists here either; this was
 * explicitly verified during the Phase 9 architecture review. The one real external call this
 * package makes — {@link ai.careerpilot.runtime.LangGraphWorkflowExecutor} invoking the existing
 * {@link ai.careerpilot.agent.AgentServiceClient} — is a generic HTTP transport call with an
 * opaque payload, not a business integration; it deliberately does not go through {@code
 * ai.careerpilot.service.WorkflowService} (which assembles its payload from {@code Resume}/{@code
 * Job} rows — real business logic this runtime must not own).
 *
 * <h2>Architectural ownership</h2>
 * Like {@code ai.careerpilot.workflowplanner} and {@code ai.careerpilot.missionexecution}, this
 * package is a top-level sibling, not nested under {@code ai.careerpilot.mission} — ownership is
 * established by whoever calls {@link ai.careerpilot.runtime.WorkflowRuntime}, not by package
 * placement. Per the target architecture, the Mission Orchestrator is the intended future caller,
 * handing this runtime one {@link ai.careerpilot.missionexecution.ExecutionDecision} at a time
 * from a {@link ai.careerpilot.missionexecution.MissionExecutionPlan} it already has.
 *
 * <h2>Not wired into anything yet — deliberately</h2>
 * Per this phase's own dark-launch requirement, nothing outside this package references {@link
 * ai.careerpilot.runtime.WorkflowRuntime} today. Wiring the Mission Orchestrator (or a future
 * Approval Manager, once built) to actually call {@code execute(...)} is left for a future phase,
 * matching the same "foundation now, connect later" discipline as every prior Phase 8/9/10/11
 * sub-phase before its own "connect it" phase.
 *
 * <h2>Explicitly out of scope for this phase</h2>
 * No Resume/Interview/Job/Country/Skill-Gap workflow implementation, no Daily Coach, no
 * Autonomous Agent change, no Approval Manager (doesn't exist yet — {@link
 * ai.careerpilot.runtime.ExecutionRequestValidator} rejects {@code APPROVAL_REQUIRED} decisions
 * rather than silently executing past a control that isn't built), no Execution History (already
 * exists, unduplicated, in {@code ai.careerpilot.missionexecution.ExecutionHistory}), no Spring
 * AI prompts, no MCP tools, no AI Gateway routing changes. Those all remain future phases.
 *
 * <h2>Lifecycle</h2>
 * {@link ai.careerpilot.runtime.DefaultWorkflowRuntime} walks, in order: {@link
 * ai.careerpilot.runtime.ExecutionRequestValidator} (structural validation) → {@link
 * ai.careerpilot.runtime.WorkflowRegistryAdapter} (resolve the Workflow Registry, Phase 4,
 * definition) → {@link ai.careerpilot.runtime.WorkflowContextFactory} → {@link
 * ai.careerpilot.runtime.WorkflowStateFactory} → {@link
 * ai.careerpilot.runtime.WorkflowLifecycleManager#begin} (opens an {@link
 * ai.careerpilot.runtime.ExecutionTrace}) → {@link ai.careerpilot.runtime.WorkflowExecutor#execute}
 * ({@link ai.careerpilot.runtime.LangGraphWorkflowExecutor} today) → {@link
 * ai.careerpilot.runtime.WorkflowLifecycleManager#complete}/{@code fail} → {@link
 * ai.careerpilot.runtime.WorkflowResultMapper} → {@link ai.careerpilot.runtime.WorkflowMetrics#record}
 * → returns one {@link ai.careerpilot.runtime.WorkflowExecutionResult}. Never throws past its own
 * boundary — every failure path (validation, not-found, timeout, cancellation, unexpected error)
 * is caught and mapped into a terminal result with a populated {@code errors} list, never silently
 * discarded.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.runtime.WorkflowExecutionRequest} / {@link
 *       ai.careerpilot.runtime.WorkflowExecutionResult} — the public input/output.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowExecutionContext} / {@link
 *       ai.careerpilot.runtime.WorkflowState} — the internal, per-execution data model built along
 *       the way.</li>
 *   <li>{@link ai.careerpilot.runtime.ExecutionRequestValidator} / {@link
 *       ai.careerpilot.runtime.DefaultExecutionRequestValidator}.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowRegistryAdapter} / {@link
 *       ai.careerpilot.runtime.DefaultWorkflowRegistryAdapter} — the only seam into {@code
 *       ai.careerpilot.workflowregistry}.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowContextFactory} / {@link
 *       ai.careerpilot.runtime.WorkflowStateFactory} and their {@code Default} implementations.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowExecutor} / {@link
 *       ai.careerpilot.runtime.LangGraphWorkflowExecutor} — the clean, swappable client
 *       abstraction for crossing into the Python AI Execution Plane; a future transport (a
 *       different protocol to the same or another AI Execution Plane) or test double plugs in
 *       without any caller of {@link ai.careerpilot.runtime.WorkflowRuntime} changing. Swapping
 *       this never means Java hosting graph execution itself.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowLifecycleManager} / {@link
 *       ai.careerpilot.runtime.DefaultWorkflowLifecycleManager} — timing/event bookkeeping via
 *       {@link ai.careerpilot.runtime.ExecutionTrace}/{@link ai.careerpilot.runtime.ExecutionEvent}.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowResultMapper} / {@link
 *       ai.careerpilot.runtime.DefaultWorkflowResultMapper}.</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowMetrics} / {@link
 *       ai.careerpilot.runtime.InMemoryWorkflowMetrics} — the observability extension point
 *       (counters only; no distributed tracing, per this phase's explicit scope).</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowRuntime} / {@link
 *       ai.careerpilot.runtime.DefaultWorkflowRuntime} — the coordinating facade over this
 *       package's own small collaborators (lifecycle sequencing only — it does not orchestrate AI
 *       agents; agent orchestration is Python's responsibility, entirely inside {@code
 *       agent-service/}).</li>
 *   <li>{@link ai.careerpilot.runtime.WorkflowRuntimeConfiguration} — the only place any bean here
 *       is constructed, gated by the single {@code runtime.enabled} flag (default {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.runtime;
