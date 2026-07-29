/**
 * Phase 11.6 — Autonomous Career Agent, the final sub-phase of the Phase 11 Enterprise AI
 * Intelligence Platform master plan. Closes the loop: observe (Phase 11.5's {@code
 * CareerMonitor}) → plan ({@link ai.careerpilot.career.agent.AgentPlanner}) → dispatch → reflect
 * → remember.
 *
 * <h2>Task dispatch is deliberately deferred, not real</h2>
 * Every one of the 8 autonomous task categories the phase spec names already has a real,
 * existing engine somewhere in this codebase (daily job discovery, ATS resume optimization,
 * Phase 3A application tracking, interview planning, career/learning roadmap generation, skill
 * gap detection, salary trend monitoring via offer analysis). Per the Phase 11 mission's
 * explicit "DO NOT replace any existing subsystem," this phase does not wire autonomous
 * dispatch into any of them — {@link ai.careerpilot.career.agent.DeferredAgentTaskExecutor} (the
 * only {@link ai.careerpilot.career.agent.AgentTaskExecutor} shipped here) always returns {@link
 * ai.careerpilot.career.agent.TaskOutcome#DEFERRED}. The observe/plan/reflect loop is genuinely
 * exercised end-to-end against real signals; only the final "actually do it" step is a
 * documented placeholder, exactly the same honesty as {@code
 * ai.careerpilot.mcp.tool.context7.Context7ApiClient}'s "best-effort, not live-verified" caveat.
 *
 * <h2>No real scheduler</h2>
 * {@link ai.careerpilot.career.agent.TaskScheduler} is a cadence/eligibility check, not Spring's
 * {@code @Scheduled} — nothing in this phase triggers {@link
 * ai.careerpilot.career.agent.AutonomousCareerAgent#runOnce} automatically. A future phase
 * wanting a real recurring trigger would add one, guarded by the same eligibility check.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.career.agent.AgentTaskType} — the 8 task categories.</li>
 *   <li>{@link ai.careerpilot.career.agent.AgentObservation} — wraps Phase 11.5's {@code
 *       CareerInsights} (or is empty if that layer is off/unavailable).</li>
 *   <li>{@link ai.careerpilot.career.agent.AgentPlanner} / {@link
 *       ai.careerpilot.career.agent.DefaultAgentPlanner} — maps observed signals to tasks.</li>
 *   <li>{@link ai.careerpilot.career.agent.AgentExecutionPlan} — the planner's output.</li>
 *   <li>{@link ai.careerpilot.career.agent.AgentTaskExecutor} / {@link
 *       ai.careerpilot.career.agent.DeferredAgentTaskExecutor} — dispatch (deferred, see above).</li>
 *   <li>{@link ai.careerpilot.career.agent.TaskScheduler} / {@link
 *       ai.careerpilot.career.agent.DefaultTaskScheduler} — run-cadence eligibility.</li>
 *   <li>{@link ai.careerpilot.career.agent.AgentMemory} / {@link
 *       ai.careerpilot.career.agent.InMemoryAgentMemory} — bounded per-user reflection history.</li>
 *   <li>{@link ai.careerpilot.career.agent.AgentReflection} — one run's full self-assessment.</li>
 *   <li>{@link ai.careerpilot.career.agent.AgentMetrics} / {@link
 *       ai.careerpilot.career.agent.InMemoryAgentMetrics} — run/task/skip counts, latency.</li>
 *   <li>{@link ai.careerpilot.career.agent.AutonomousCareerAgent} / {@link
 *       ai.careerpilot.career.agent.DefaultAutonomousCareerAgent} — the orchestrating facade.</li>
 *   <li>{@link ai.careerpilot.career.agent.AutonomousCareerAgentConfig} — the only place any
 *       bean here is constructed, gated by the single {@code career.agent.enabled} flag
 *       (default {@code false}).</li>
 * </ul>
 *
 * <h2>Phase 11 complete</h2>
 * This closes the six-part Phase 11 master plan: Intent Engine (11.1) → Capability Planner
 * (11.2) → Multi-Capability Execution (11.3) → Enterprise Memory Evolution (11.4) → Proactive
 * Career Intelligence (11.5) → Autonomous Career Agent (11.6). Every sub-phase shipped
 * dark-by-default and additive; none replaced an existing subsystem; only Phase 10.4 (a separate,
 * earlier master plan) actually connected new orchestration to a live request path. Wiring any
 * of Phase 11's layers into production — Copilot, a scheduler, or real task dispatch — is future
 * work this series deliberately left open.
 *
 * <h2>Phase 7A — Mission-Aware Autonomous Career Agent</h2>
 * Evolves this same {@link ai.careerpilot.career.agent.DefaultAutonomousCareerAgent} — no
 * {@code AgentV2}, no {@code MissionAgent}, no second planner — into a Mission-aware execution
 * coordinator. Mission-awareness is one more optional observation source, the same shape {@code
 * CareerMonitor} already has: {@link ai.careerpilot.career.agent.MissionContext} (new) carries
 * data read verbatim from {@code ai.careerpilot.mission.MissionAwareDailyBriefService} (Phase
 * 6A) — this package still owns zero Mission/Strategy/Workflow-Registry business rules; {@code
 * MissionOrchestratorService} (Phase 5) remains the only thing that decides "what should run
 * next," this agent only translates that decision list into its own {@link
 * ai.careerpilot.career.agent.AgentTaskType} vocabulary via {@link
 * ai.careerpilot.career.agent.DefaultAgentPlanner} (the same, unmodified interface, extended not
 * duplicated).
 *
 * <p>Gated by its own {@code career.mission.agent.enabled} flag (default {@code false},
 * independent of {@code career.agent.enabled}) — off, {@link
 * ai.careerpilot.career.agent.DefaultAutonomousCareerAgent} never even looks at {@code
 * MissionAwareDailyBriefService}, byte-identical to pre-7A behavior.
 *
 * <p>Two forward-looking, currently-inert preparations, matching this codebase's established
 * "foundation now, wiring later" discipline (e.g. {@code ai.springai} Phase 9.1, {@code
 * ai.careerpilot.mcp} Phase 10.1):
 * <ul>
 *   <li>{@link ai.careerpilot.career.agent.WorkflowType} — a richer taxonomy (RESUME,
 *       JOB_DISCOVERY, ATS, INTERVIEW, LEARNING, OFFER, VISA, RELOCATION, COMPANY, SALARY) plus a
 *       pure {@code forAgentTask(AgentTaskType)} translation table, prepared for a future
 *       LangGraph-dispatching {@link ai.careerpilot.career.agent.AgentTaskExecutor} — not called
 *       by {@link ai.careerpilot.career.agent.DeferredAgentTaskExecutor} or anything else today.</li>
 *   <li>{@code ObjectProvider<ai.careerpilot.mission.WorkflowPlanner>} — reuses Phase 6A.1's
 *       extension-point interface (not a second one) as a provisioned-but-unused seam on {@link
 *       ai.careerpilot.career.agent.DefaultAutonomousCareerAgent} for a future, richer Workflow
 *       Planner. No bean implements it; nothing calls it.</li>
 * </ul>
 *
 * <p>This agent never calls {@code AiGatewayService}, Spring AI, or any MCP tool handler
 * directly, and never bypasses {@code SafetyEngine}/{@code ApprovalService} — {@link
 * ai.careerpilot.career.agent.DeferredAgentTaskExecutor} is still the only {@link
 * ai.careerpilot.career.agent.AgentTaskExecutor}, so no real dispatch, approval, or AI call
 * happens as a result of this phase.
 */
package ai.careerpilot.career.agent;
