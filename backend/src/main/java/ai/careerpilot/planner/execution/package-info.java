/**
 * Phase 11.3 — Multi-Capability Execution. Turns Phase 11.2's {@code CapabilityPlan} (a plan,
 * not yet run) into a real, executed result: multiple capabilities — resume, GitHub, memory,
 * jobs, career strategy — running in one request, respecting dependency order, tolerating
 * partial failures, and retrying transient ones.
 *
 * <h2>Not wired into anything yet</h2>
 * Per the Phase 11 target architecture, no controller or business service calls {@link
 * ai.careerpilot.planner.execution.ExecutionCoordinator} yet — that connection (Copilot →
 * Intent Engine → Capability Planner → *this layer* → Spring AI synthesis) is out of scope for
 * Phase 11.3, matching the same incremental-delivery discipline as every prior Phase 11
 * sub-phase.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.planner.execution.ExecutionGraph} — a plan's stages, re-resolved
 *       from bare {@code CapabilityType}s back into full {@code CapabilityStep}s.</li>
 *   <li>{@link ai.careerpilot.planner.execution.CapabilityExecutor} / {@link
 *       ai.careerpilot.planner.execution.DefaultCapabilityExecutor} — executes ONE capability
 *       (resolves its MCP tools via the existing Phase 10.3 {@code ToolSelectionEngine}, runs
 *       them via the existing Phase 10.2 {@code McpExecutor} — never a tool handler directly),
 *       with whole-step retries on failure.</li>
 *   <li>{@link ai.careerpilot.planner.execution.ParallelCapabilityExecutor} / {@link
 *       ai.careerpilot.planner.execution.DefaultParallelCapabilityExecutor} — runs one stage's
 *       capabilities in parallel via {@code CompletableFuture} (safe because {@code
 *       PlanOptimizer} already guarantees no intra-stage dependency).</li>
 *   <li>{@link ai.careerpilot.planner.execution.ExecutionCoordinator} / {@link
 *       ai.careerpilot.planner.execution.DefaultExecutionCoordinator} — walks every stage in
 *       order, sequential between stages, parallel within; a failed capability never stops the
 *       plan.</li>
 *   <li>{@link ai.careerpilot.planner.execution.ResultMerger} / {@link
 *       ai.careerpilot.planner.execution.DefaultResultMerger} — combines every capability's
 *       result into one structured {@link ai.careerpilot.planner.execution.MergedExecutionContext}
 *       (not raw text concatenation).</li>
 *   <li>{@link ai.careerpilot.planner.execution.ExecutionResult} / {@link
 *       ai.careerpilot.planner.execution.MultiCapabilityResult} — per-capability and whole-plan
 *       outcomes.</li>
 *   <li>{@link ai.careerpilot.planner.execution.MultiCapabilityMetrics} / {@link
 *       ai.careerpilot.planner.execution.InMemoryMultiCapabilityMetrics} — execution time,
 *       retries, partial failures, stage size, plan latency.</li>
 *   <li>{@link ai.careerpilot.planner.execution.MultiCapabilityExecutionConfig} — the only place
 *       any bean here is constructed, gated by the single {@code multi.capability.enabled} flag
 *       (default {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.planner.execution;
