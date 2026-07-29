/**
 * Pre-Phase-9 Architecture Hardening — Mission Execution Engine. Sits between the Workflow
 * Planner (Phase 8, unchanged) — which decides HOW a single workflow should execute — and the
 * LangGraph Workflow Runtime ({@code ai.careerpilot.runtime}, Phase 9) — the Java Control Plane's
 * client into the Python AI Execution Plane that actually executes it. This package
 * closes the gap the Workflow Planner alone couldn't: given several {@code WorkflowPlan}s at
 * once, WHICH executes now, which waits, which run in parallel, which are blocked by a
 * precondition or a prerequisite workflow, which need approval, which retry. It decides
 * execution order and policy only — nothing in this package executes a workflow.
 *
 * <h2>Architectural ownership</h2>
 * Like {@code ai.careerpilot.workflowplanner}, this package is an <b>internal capability owned
 * by the Mission Orchestrator</b> ({@code ai.careerpilot.mission.MissionOrchestratorService},
 * Phase 5) — it decides WHEN/IN WHAT ORDER several already-planned workflows execute, as a
 * collaborator the Orchestrator will call, not a standalone pipeline stage between the
 * Orchestrator and LangGraph. It stays a top-level sibling package rather than being nested under
 * {@code ai.careerpilot.mission}: ownership is established by the future caller importing {@link
 * ai.careerpilot.missionexecution.MissionExecutionEngine}, not by file location. {@link
 * ai.careerpilot.missionexecution.ExecutionHistory} is an internal collaborator of the engine
 * itself (constructed only inside this package's own {@code MissionExecutionEngineConfig}) — it
 * is not a capability the Mission Orchestrator calls separately.
 *
 * <h2>Not another agent, orchestrator, planner, or engine</h2>
 * {@link ai.careerpilot.missionexecution.MissionExecutionEngine} has one method, {@code
 * plan(ExecutionContext)}. It has no dependency on LangGraph, Spring AI, MCP, or {@code
 * ai.careerpilot.ai.AiGatewayService} — grep confirms no import of any of those packages
 * anywhere under {@code ai.careerpilot.missionexecution}. It consumes {@code
 * ai.careerpilot.workflowplanner.WorkflowPlan} exactly as produced (the Workflow Planner is
 * completely unchanged by this phase) and never reaches into the Mission Engine, Strategy
 * Engine, or Mission Orchestrator directly — every input it needs (mission progress, metrics for
 * precondition evaluation, current workflow states, previous results) is passed in via {@link
 * ai.careerpilot.missionexecution.ExecutionContext} by the caller, matching the "never own
 * business rules" discipline established for the Mission-Aware Autonomous Career Agent (Phase 7A).
 *
 * <h2>Not wired into anything yet — deliberately</h2>
 * Per this phase's own "everything must remain dark" requirement, nothing outside this package
 * references {@link ai.careerpilot.missionexecution.MissionExecutionEngine}. Wiring the
 * Autonomous Career Agent or Mission Orchestrator to actually call {@code plan(...)} before
 * dispatch is left for a future phase, matching the same "foundation now, connect later"
 * discipline as {@code ai.careerpilot.workflowplanner} (Phase 8) and every prior Phase 9/10/11
 * sub-phase before its own "connect it" phase.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionContext} / {@link
 *       ai.careerpilot.missionexecution.MissionExecutionPlan} — the input/output data model.</li>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionPolicy} / {@link
 *       ai.careerpilot.missionexecution.ExecutionPriority} / {@link
 *       ai.careerpilot.missionexecution.ExecutionState} — the three enums this layer adds, all
 *       intentionally distinct from Phase 8's {@code WorkflowExecutionStrategy}/{@code
 *       WorkflowPriority} rather than modifying them (zero breaking changes to shipped code).</li>
 *   <li>{@link ai.careerpilot.missionexecution.Precondition} — an evaluable metric gate (e.g.
 *       "Resume Score &gt;= 90"), evaluated against caller-supplied {@link
 *       ai.careerpilot.missionexecution.ExecutionContext#metrics()} — this package never computes
 *       a resume score, LinkedIn completeness, etc. itself.</li>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionPriorityResolver} / {@link
 *       ai.careerpilot.missionexecution.DefaultExecutionPriorityResolver} — Phase 8
 *       {@code WorkflowPriority} → this layer's {@code ExecutionPriority}.</li>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionDependencyResolver} / {@link
 *       ai.careerpilot.missionexecution.DefaultExecutionDependencyResolver} — precondition +
 *       prerequisite-workflow blocking.</li>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionScheduler} / {@link
 *       ai.careerpilot.missionexecution.DefaultExecutionScheduler} — week-number assignment from
 *       a fixed natural mission sequence, matching the phase spec's own worked example.</li>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionEstimator} / {@link
 *       ai.careerpilot.missionexecution.DefaultExecutionEstimator} — aggregate, plan-level
 *       estimates derived from each {@code WorkflowPlan}'s own Phase 8 estimate.</li>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionValidator} / {@link
 *       ai.careerpilot.missionexecution.DefaultExecutionValidator} — structural sanity checks.</li>
 *   <li>{@link ai.careerpilot.missionexecution.ExecutionHistory} / {@link
 *       ai.careerpilot.missionexecution.InMemoryExecutionHistory} — bounded per-mission run
 *       history, for a future Expected-vs-Actual replanning phase.</li>
 *   <li>{@link ai.careerpilot.missionexecution.MissionExecutionEngine} / {@link
 *       ai.careerpilot.missionexecution.DefaultMissionExecutionEngine} — the orchestrating
 *       facade.</li>
 *   <li>{@link ai.careerpilot.missionexecution.MissionExecutionEngineConfig} — the only place any
 *       bean here is constructed, gated by the single {@code execution.engine.enabled} flag
 *       (default {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.missionexecution;
