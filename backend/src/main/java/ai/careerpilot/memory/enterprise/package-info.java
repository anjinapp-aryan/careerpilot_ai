/**
 * Phase 11.4 — Enterprise Memory Evolution. Adds a classification/consolidation layer over the
 * eight memory categories named in the phase spec (Working/Long-Term/Career/Decision/
 * Conversation/Skill/Preference/Learning) — <b>additive, not a replacement</b>:
 * <ul>
 *   <li>{@code ai.careerpilot.memory.CareerMemoryService} (Phase 7.15.1, Career Decision Memory)
 *       remains the system of record for career decisions — untouched, still the backing store
 *       for the Phase 10.2 Memory MCP server.</li>
 *   <li>{@code ai.careerpilot.memory.conversation} (conversation-derived decision extraction)
 *       remains untouched.</li>
 *   <li>{@code ai.careerpilot.service.ConversationMemory} (Copilot chat history) remains
 *       untouched.</li>
 * </ul>
 * This package is a new, broader classification/importance/consolidation layer sitting
 * alongside those systems — genuinely functional and tested in isolation, but nothing in this
 * phase rewires any of the above to use it.
 *
 * <h2>In-memory only</h2>
 * Per the phase's "ZERO database breaking changes" requirement, {@link
 * ai.careerpilot.memory.enterprise.InMemoryMemoryStore} is exactly that — in-process, not
 * persisted, no new Flyway migration. A future phase wanting durability would replace that one
 * class, not the interfaces built around it.
 *
 * <h2>Not wired into anything yet</h2>
 * No controller or business service calls {@link ai.careerpilot.memory.enterprise.MemoryManager}
 * yet — matching the same incremental-delivery discipline as every prior Phase 11 sub-phase.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryType} — the 8-category taxonomy.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryImportance} — a score plus a derived
 *       CRITICAL/HIGH/MEDIUM/LOW band.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryEntry} — one stored memory (immutable, with-style updates).</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryPolicy} — TTL/promotion/capacity rules.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryClassifier} / {@link
 *       ai.careerpilot.memory.enterprise.DefaultMemoryClassifier} — keyword-based type + importance
 *       classification, the ML-swap seam.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryManager} / {@link
 *       ai.careerpilot.memory.enterprise.DefaultMemoryManager} — remember/forget/list, enforces
 *       per-type capacity.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryRetriever} / {@link
 *       ai.careerpilot.memory.enterprise.DefaultMemoryRetriever} — ranked retrieval, marks
 *       access (feeds consolidation).</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemorySearch} / {@link
 *       ai.careerpilot.memory.enterprise.DefaultMemorySearch} — free-text search.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryConsolidator} / {@link
 *       ai.careerpilot.memory.enterprise.DefaultMemoryConsolidator} — the WORKING → durable-type
 *       lifecycle: promote-on-frequent-access, evict-on-stale-and-unused.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.MemoryMetrics} / {@link
 *       ai.careerpilot.memory.enterprise.InMemoryMemoryMetrics} — remember/forget counts,
 *       retrieval/search latency, consolidation counts.</li>
 *   <li>{@link ai.careerpilot.memory.enterprise.EnterpriseMemoryConfig} — the only place any
 *       bean here is constructed, gated by the single {@code enterprise.memory.enabled} flag
 *       (default {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.memory.enterprise;
