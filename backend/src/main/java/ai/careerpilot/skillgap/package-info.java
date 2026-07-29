/**
 * Phase 10 — Skill Gap Intelligence Workflow: the first business workflow built on top of the
 * frozen Phase 9 platform (Mission Engine → Strategy Engine → Mission Orchestrator → Workflow
 * Planner → Mission Execution Engine → Workflow Registry, none of which this phase modifies).
 * Answers "what skills does the user need to achieve their career mission" by invoking a
 * dedicated LangGraph graph in the Python AI Execution Plane ({@code agent-service/app/skillgap/})
 * and persisting/returning its structured result.
 *
 * <h2>Java Control Plane responsibilities (this package)</h2>
 * <ul>
 *   <li>Workflow registration — one {@code workflow_definition} row seeded by {@code
 *       V79__skill_gap_analysis.sql}, read via the existing, unmodified {@code
 *       WorkflowRegistryService.latestForType(String)}.</li>
 *   <li>Input validation — mission ownership ({@link ai.careerpilot.mission.MissionNotFoundException})
 *       and the {@code skillgap.workflow.enabled} flag gate.</li>
 *   <li>Building the transport payload from {@link ai.careerpilot.domain.CareerMission} — data
 *       marshalling, not AI reasoning (same discipline as {@code WorkflowService.assembleAgentInput}
 *       for the main graph).</li>
 *   <li>Persistence and history — {@link ai.careerpilot.domain.SkillGapAnalysis}, one row per run,
 *       append-only.</li>
 *   <li>The REST API — {@link ai.careerpilot.api.SkillGapController}.</li>
 * </ul>
 * Nothing here parses or re-derives the business meaning of the AI Execution Plane's response —
 * {@link ai.careerpilot.skillgap.SkillGapAgentResponse} is stored and returned verbatim as an
 * opaque {@code Map<String, Object>} (see {@link ai.careerpilot.api.dto.SkillGapDtos}).
 *
 * <h2>Python AI Execution Plane responsibilities ({@code agent-service/app/skillgap/})</h2>
 * The LangGraph graph, its state, all six agents (Mission Context, Resume Intelligence, Market
 * Intelligence, Skill Gap, Learning Roadmap, Mission Readiness), prompt orchestration, agent
 * execution, skill comparison, learning roadmap generation, and mission readiness evaluation.
 * Java never reimplements any of this.
 *
 * <h2>Why this package has its own {@link ai.careerpilot.skillgap.SkillGapAgentServiceClient}
 * instead of reusing {@code ai.careerpilot.agent.AgentServiceClient}</h2>
 * The frozen Phase 9 "AI Execution Client" ({@code ai.careerpilot.runtime}, especially {@code
 * LangGraphWorkflowExecutor}) and {@code ai.careerpilot.agent.AgentServiceClient} must not be
 * modified per the architecture freeze. {@code AgentServiceClient.startRun} posts to the existing,
 * live, shared {@code /runs} endpoint — the main 8-node career graph. Routing this workflow
 * through it would require either misusing that unrelated endpoint (posting skill-gap-shaped data
 * to a graph that doesn't expect it) or modifying a live, shared, already-in-production entry
 * point — both carry real regression risk to the platform's primary workflow, which "zero
 * breaking changes" rules out. A second, small, isolated client calling a second, dedicated
 * Python endpoint ({@code POST /skill-gap/runs}) is the lower-risk choice, matching this
 * codebase's own established precedent of mirroring rather than modifying (e.g.
 * {@code InternationalJobRankingService} mirrors {@code JobMatchingService.refreshForUser}'s
 * shape rather than changing it). {@code ai.careerpilot.runtime.WorkflowRuntime} remains exactly
 * as Phase 9 left it — unwired, dark, unmodified; a future phase could migrate this workflow onto
 * it once the Mission Orchestrator's own wiring to {@code WorkflowRuntime} happens generally.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.skillgap.SkillGapWorkflowService} — the only orchestrator; trigger/
 *       latest/history.</li>
 *   <li>{@link ai.careerpilot.skillgap.SkillGapAgentServiceClient} / {@link
 *       ai.careerpilot.skillgap.SkillGapAgentResponse} — the transport client and its response
 *       shape.</li>
 *   <li>{@link ai.careerpilot.skillgap.SkillGapAnalysisNotFoundException} — 404 when no analysis
 *       exists yet for a mission.</li>
 *   <li>{@link ai.careerpilot.api.SkillGapController} — {@code POST /api/skill-gap/{missionId}/run},
 *       {@code GET .../latest}, {@code GET .../history}.</li>
 * </ul>
 *
 * Gated by {@code skillgap.workflow.enabled} (default {@code false}, inline {@code @Value}, no
 * {@code application.yml} entry — same convention as the sibling {@code copilot.*}/{@code
 * career.*} flags).
 *
 * <h2>Naming note — not the same thing as {@code learning.career.goal.SkillGapIntelligenceService}</h2>
 * Phase 7.19.2's {@code SkillGapIntelligenceService} (gated {@code career.skill-gap.enabled}) is a
 * deterministic, no-LLM frequency count over already-persisted {@code job_recommendations}
 * matched/missing skills — it has no Mission input, no market/country comparison, no learning
 * roadmap with duration/difficulty, and no readiness/confidence/mission-progress output. This
 * package is a different, AI-powered, Mission-driven workflow with a genuinely different output
 * shape. The two are intentionally unrelated and neither calls the other; a future phase could
 * reconcile them (e.g. feeding Phase 7.19.2's frequency data into this workflow's Market
 * Intelligence Agent as an additional signal) but that reconciliation is out of scope here.
 */
package ai.careerpilot.skillgap;
