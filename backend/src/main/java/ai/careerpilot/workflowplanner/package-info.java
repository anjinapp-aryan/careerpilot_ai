/**
 * Phase 8 — Enterprise Workflow Planner. Sits between the Mission Engine (Phase 1) / Mission
 * Orchestrator (Phase 5) / Mission-Aware Daily Coach (Phase 6A) / Mission-Aware Autonomous Career
 * Agent (Phase 7A) — which decide WHAT should happen — and the Python AI Execution Plane (LangGraph
 * Runtime, reached via {@code ai.careerpilot.runtime}, Phase 9), Spring AI, MCP, and the AI Gateway
 * — which decide/execute HOW downstream of this package's plan. This
 * package decides HOW a requested capability *should* execute (which steps, what strategy,
 * estimated cost/duration, approval/retry/fallback policy) and produces exactly one artifact, a
 * {@link ai.careerpilot.workflowplanner.WorkflowPlan} — it never executes anything.
 *
 * <h2>Architectural ownership</h2>
 * This package is an <b>internal planning capability owned by the Mission Orchestrator</b>
 * ({@code ai.careerpilot.mission.MissionOrchestratorService}, Phase 5) — not an independent stage
 * in a serial Mission-Engine-to-LangGraph pipeline, and not a peer of Mission Engine/Strategy
 * Engine at the top level. The relationship is expressed the ordinary Java way: the Orchestrator
 * will import and call {@link ai.careerpilot.workflowplanner.WorkflowPlanner}, the same
 * calls-a-sibling-package shape as {@code WorkflowService} calling {@code AgentServiceClient} —
 * deliberately <b>not</b> nested under {@code ai.careerpilot.mission}, since Java ownership is
 * about who calls whom, not package placement, and nesting would force a mechanical file-move
 * across every class here for zero behavioral gain. As of this phase, the Orchestrator does not
 * yet call this package (see "Not wired into anything yet" below) — this note describes the
 * intended ownership shape for the future wiring phase, not the current call graph.
 *
 * <h2>It is not another agent, orchestrator, or engine</h2>
 * {@link ai.careerpilot.workflowplanner.WorkflowPlanner} has one method, {@code plan(...)}. It
 * has no dependency on LangGraph, Spring AI, MCP, or {@code ai.careerpilot.ai.AiGatewayService} —
 * grep confirms no import of any of those packages anywhere under {@code
 * ai.careerpilot.workflowplanner}. It never hardcodes a workflow's existence: {@link
 * ai.careerpilot.workflowplanner.WorkflowSelector} always asks the existing Workflow Registry
 * (Phase 4, {@code ai.careerpilot.workflowregistry.WorkflowRegistryService}) for the current
 * definition/version/required-capabilities, via a new non-throwing {@code latestForType} lookup
 * added to that existing service (one new read method, zero change to its existing behavior).
 *
 * <h2>Not wired into anything yet — deliberately</h2>
 * Per this phase's own "everything must remain dark" requirement, nothing outside this package
 * references {@link ai.careerpilot.workflowplanner.WorkflowPlanner} — the Mission Engine, Mission
 * Orchestrator, Daily Coach, and Autonomous Career Agent are all byte-for-byte unchanged. Wiring
 * the Autonomous Agent's dispatch to actually call {@code WorkflowPlanner.plan(...)} before
 * handing off to {@code DeferredAgentTaskExecutor} — the target shape this phase's spec
 * describes — is left for a future phase, matching the same "foundation now, connect later"
 * discipline as every prior Phase 9/10/11 sub-phase before its own "connect it" phase (e.g. Phase
 * 10.4 connected the Capability Engine to the Copilot three phases after Phase 10.1 built it).
 *
 * <h2>Naming note — two different "WorkflowPlanner" concepts, not a duplicate</h2>
 * {@code ai.careerpilot.mission.WorkflowPlanner} (Phase 6A.1) is a narrow, provisional extension
 * point for the Daily Coach's own "which tasks are today's tasks" question ({@code
 * List<String> planTodaysTasks(CareerMission, List<CareerGoal>)}) — it predates this phase's full
 * spec and was deliberately kept unchanged here to avoid regression risk to already-shipped,
 * tested Phase 6A.1/7A code. {@link ai.careerpilot.workflowplanner.WorkflowPlanner} (this
 * package) is the authoritative, richer planner the whole Phase 6–8 series has been building
 * toward. The two are intentionally separate interfaces for separate concerns; a future phase may
 * reconcile them once real task selection is wired to consult the real planner.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowType} — 15 workflow families, each mapped
 *       to its Workflow Registry {@code workflow_type} key.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowPlanRequest} / {@link
 *       ai.careerpilot.workflowplanner.WorkflowPlan} / {@link
 *       ai.careerpilot.workflowplanner.WorkflowStep} — the input/output data model.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowSelector} / {@link
 *       ai.careerpilot.workflowplanner.DefaultWorkflowSelector} — resolves a definition via the
 *       existing Workflow Registry.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowStepTemplateProvider} / {@link
 *       ai.careerpilot.workflowplanner.DefaultWorkflowStepTemplateProvider} — the per-type step
 *       blueprint (e.g. the Resume Workflow's Analyze → ATS Optimize → Generate Improvements →
 *       Approval).</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowDependencyResolver} / {@link
 *       ai.careerpilot.workflowplanner.DefaultWorkflowDependencyResolver} — splits steps into
 *       sequential vs. parallel-safe groups.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowEstimator} / {@link
 *       ai.careerpilot.workflowplanner.DefaultWorkflowEstimator} — deterministic
 *       duration/token/cost/complexity/confidence estimates, never measured.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowPlanFactory} / {@link
 *       ai.careerpilot.workflowplanner.DefaultWorkflowPlanFactory} — assembles the final plan.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowValidator} / {@link
 *       ai.careerpilot.workflowplanner.DefaultWorkflowValidator} — structural sanity checks.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowPlanner} / {@link
 *       ai.careerpilot.workflowplanner.DefaultWorkflowPlanner} — the orchestrating facade.</li>
 *   <li>{@link ai.careerpilot.workflowplanner.WorkflowPlannerConfig} — the only place any bean
 *       here is constructed, gated by the single {@code workflow.planner.enabled} flag (default
 *       {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.workflowplanner;
