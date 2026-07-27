/**
 * Phase 11.1 — Enterprise Intent Engine. Replaces "keyword matching only" with a formal
 * classify-with-confidence pipeline: {@link ai.careerpilot.intent.IntentResolver} (raw, scored
 * candidates — an ML-swappable seam) → {@link ai.careerpilot.intent.IntentClassifier} (ranked,
 * priority-tie-broken) → {@link ai.careerpilot.intent.IntentEngine} (accept/fallback decision +
 * {@link ai.careerpilot.intent.IntentHistory} + {@link ai.careerpilot.intent.IntentMetrics}).
 *
 * <h2>Not wired into the Copilot yet</h2>
 * Per the Phase 11 target architecture (Copilot → Intent Engine → Capability Planner →
 * Capability Engine), this package is a standalone foundation layer — genuinely functional and
 * tested in isolation, but no controller or business service calls {@link
 * ai.careerpilot.intent.IntentEngine} yet. The not-yet-built Phase 11.2 Capability Planner is
 * what will map a classified {@link ai.careerpilot.intent.IntentType} down to one or more {@code
 * ai.careerpilot.capability.CapabilityType}s and route into the existing Phase 10.3/10.4
 * pipeline — connecting this layer to {@code CopilotService} is explicitly out of scope for
 * Phase 11.1.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.intent.IntentType} — the intent taxonomy (7 values, richer than
 *       {@code CapabilityType} — includes {@code EXECUTIVE_COACH}, which has no Phase 10.3
 *       capability equivalent yet).</li>
 *   <li>{@link ai.careerpilot.intent.IntentDefinition} / {@link ai.careerpilot.intent.IntentRegistry}
 *       — metadata (keywords, priority) per intent; {@link ai.careerpilot.intent.InMemoryIntentRegistry}
 *       pre-populates all 7.</li>
 *   <li>{@link ai.careerpilot.intent.IntentResolver} / {@link ai.careerpilot.intent.KeywordIntentResolver}
 *       — raw candidate scoring from free text.</li>
 *   <li>{@link ai.careerpilot.intent.IntentClassifier} / {@link ai.careerpilot.intent.DefaultIntentClassifier}
 *       — ranking + priority tie-breaking.</li>
 *   <li>{@link ai.careerpilot.intent.IntentConfidence} — a score plus a derived HIGH/MEDIUM/LOW band.</li>
 *   <li>{@link ai.careerpilot.intent.IntentCandidate} / {@link ai.careerpilot.intent.IntentResult}
 *       — a single scored candidate, and the engine's full ranked verdict (multiple candidates,
 *       not just the winner).</li>
 *   <li>{@link ai.careerpilot.intent.IntentHistory} / {@link ai.careerpilot.intent.InMemoryIntentHistory}
 *       — bounded per-user recent-intent ring buffer, for a future planner to consult.</li>
 *   <li>{@link ai.careerpilot.intent.IntentMetrics} / {@link ai.careerpilot.intent.InMemoryIntentMetrics}
 *       — latency, confidence, selection, fallback counters.</li>
 *   <li>{@link ai.careerpilot.intent.IntentEngine} / {@link ai.careerpilot.intent.DefaultIntentEngine}
 *       — the orchestrator; never throws, always returns a reasoned result.</li>
 *   <li>{@link ai.careerpilot.intent.IntentConfig} — the only place any bean here is constructed,
 *       gated by the single {@code intent.engine.enabled} flag (default {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.intent;
