/**
 * Phase 11.2 — Capability Planner. Bridges Phase 11.1's Intent Engine and Phase 10.3's
 * Capability Engine: turns "one question → one capability" into "one question → a plan of one
 * or more capabilities, dependency-ordered into parallel-safe stages."
 *
 * <h2>Not wired into anything yet</h2>
 * Per the Phase 11 target architecture (Copilot → Intent Engine → Capability Planner →
 * Capability Engine → ...), this package produces {@link ai.careerpilot.planner.CapabilityPlan}s
 * but does not execute them — that is explicitly Phase 11.3's job ({@code CapabilityExecutor},
 * {@code ParallelCapabilityExecutor}, not yet built). No controller or business service calls
 * {@link ai.careerpilot.planner.CapabilityPlanner} yet.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.planner.CapabilityPriority} — CRITICAL/HIGH/MEDIUM/LOW band per step.</li>
 *   <li>{@link ai.careerpilot.planner.CapabilityStep} — one {@code CapabilityType} (Phase 10.3,
 *       unchanged) plus its priority.</li>
 *   <li>{@link ai.careerpilot.planner.CapabilityDependencies} — a dependency graph over
 *       capability types; empty for most intents, non-empty only where sequencing genuinely
 *       matters (see {@link ai.careerpilot.planner.DefaultCapabilityPlanner}'s
 *       {@code EXECUTIVE_COACH} example).</li>
 *   <li>{@link ai.careerpilot.planner.PlanOptimizer} / {@link ai.careerpilot.planner.DefaultPlanOptimizer}
 *       — Kahn's-algorithm topological sort into parallel-safe stages; a dependency cycle
 *       degrades to one combined stage rather than looping or throwing.</li>
 *   <li>{@link ai.careerpilot.planner.ExecutionOrder} — the optimizer's output: ordered stages,
 *       each a list of capabilities safe to run in parallel.</li>
 *   <li>{@link ai.careerpilot.planner.CapabilityPlan} — the planner's full verdict.</li>
 *   <li>{@link ai.careerpilot.planner.CapabilityPlanner} / {@link ai.careerpilot.planner.DefaultCapabilityPlanner}
 *       — the {@code IntentType → CapabilityType} mapping and orchestrator; never throws.</li>
 *   <li>{@link ai.careerpilot.planner.CapabilityPlannerMetrics} / {@link
 *       ai.careerpilot.planner.InMemoryCapabilityPlannerMetrics} — plan latency, plan size,
 *       cycle-detection counts.</li>
 *   <li>{@link ai.careerpilot.planner.PlannerConfig} — the only place any bean here is
 *       constructed, gated by the single {@code capability.planner.enabled} flag (default
 *       {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.planner;
