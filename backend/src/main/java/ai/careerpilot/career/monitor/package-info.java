/**
 * Phase 11.5 — Proactive Career Intelligence. Detects the 7 categories of proactive insight
 * named in the phase spec — new job matches, resume outdated, missing certifications, salary
 * below market, promotion readiness, interview reminders, learning suggestions — as thin,
 * read-only wrappers around existing data, exactly the same discipline as Phase 10.2's MCP
 * tools: no new query beyond existing finder methods, no new table, no fabricated signal.
 *
 * <h2>Reuses existing data, adds no new computation</h2>
 * <ul>
 *   <li>Job matches — {@code JobRecommendationRepository} (Phase 2C).</li>
 *   <li>Resume outdated — {@code ResumeRepository}'s {@code createdAt}.</li>
 *   <li>Missing certification / learning suggestion — {@code CareerStrategy.skillGapsJson}
 *       (Phase 6.6/7.19).</li>
 *   <li>Promotion readiness — {@code CareerStrategy.promotionReadinessJson} (Phase 7.19,
 *       {@code PromotionReadinessService}'s output) — presence-only, this package does not
 *       parse or reinterpret that JSON's internal shape.</li>
 *   <li>Salary below market — {@code Offer.baseSalary} vs. {@code Offer.marketP50} (Phase 7,
 *       {@code OfferAnalysisService}'s output).</li>
 *   <li>Interview reminders — {@code Interview.scheduledAt} (Phase 3A).</li>
 * </ul>
 *
 * <h2>Not wired into anything yet</h2>
 * No scheduler, controller, or business service calls {@link
 * ai.careerpilot.career.monitor.CareerMonitor} yet — matching the same incremental-delivery
 * discipline as every prior Phase 11 sub-phase. (Compare to the existing, already-wired {@code
 * ai.careerpilot.dailydiscovery} package — that is a genuinely different, already-shipped
 * feature; this package does not replace or connect to it.)
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.career.monitor.CareerAlertType} / {@link
 *       ai.careerpilot.career.monitor.CareerAlertSeverity} / {@link
 *       ai.careerpilot.career.monitor.CareerAlert} — the alert shape.</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerOpportunityDetector} / {@link
 *       ai.careerpilot.career.monitor.DefaultCareerOpportunityDetector} — job-match detection.</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerEventEngine} / {@link
 *       ai.careerpilot.career.monitor.DefaultCareerEventEngine} — the other six categories.</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerRecommendationEngine} / {@link
 *       ai.careerpilot.career.monitor.DefaultCareerRecommendationEngine} — severity-ranked,
 *       capped prioritization.</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerTimeline} / {@link
 *       ai.careerpilot.career.monitor.InMemoryCareerTimeline} — per-user history + cooldown
 *       dedupe (in-memory only, no new table).</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerMonitor} / {@link
 *       ai.careerpilot.career.monitor.DefaultCareerMonitor} — the orchestrating facade.</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerInsights} — one run's full output.</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerMonitorMetrics} / {@link
 *       ai.careerpilot.career.monitor.InMemoryCareerMonitorMetrics} — detection/suppression
 *       counts, run latency.</li>
 *   <li>{@link ai.careerpilot.career.monitor.CareerMonitorConfig} — the only place any bean
 *       here is constructed, gated by the single {@code career.monitor.enabled} flag (default
 *       {@code false}).</li>
 * </ul>
 */
package ai.careerpilot.career.monitor;
