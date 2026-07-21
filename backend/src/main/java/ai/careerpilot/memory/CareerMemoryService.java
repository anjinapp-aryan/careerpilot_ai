package ai.careerpilot.memory;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.RecommendationFeedback;
import ai.careerpilot.repo.CareerDecisionMemoryRepository;
import ai.careerpilot.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7.15.1 — Career Decision Memory. Extends the existing context/recommendation
 * architecture with one capability none of it had: a durable, ranked, append-only record of
 * <em>why</em> the candidate made a decision, so the Copilot and future recommendations stop
 * re-asking. Deliberately NOT a rebuild of {@code learning_event} (Phase 6, pattern-computation
 * shaped) or {@code recommendation_feedback} (Phase 2C, raw reaction log) — this service reads
 * from both plus the Phase 3A workflow events, normalizes them into one retrievable shape, and
 * is the only thing in this codebase that does that normalization.
 *
 * <p>Extraction sources wired in this phase: {@link RecommendationFeedback} (has the only
 * genuine free-text "reason" field in the system — see {@code captureFeedback}), and five
 * existing Phase 3A/2D events via {@link CareerMemoryEventBridge}. Deliberately NOT wired in
 * this phase: Copilot-conversation extraction (would need LLM-based parsing — no existing infra
 * for that, and a wrong extraction silently poisoning future recommendations is a correctness
 * risk that deserves its own review, not a bundled add-on here), resume-diff extraction, and
 * dashboard-interaction extraction. Gated {@code career.memory.enabled}, default {@code false}.
 */
@Service
public class CareerMemoryService {

    private static final Logger log = LoggerFactory.getLogger(CareerMemoryService.class);

    /** Half-life-ish decay constant (days) for the freshness component of retrieval scoring. */
    private static final double FRESHNESS_DECAY_DAYS = 60.0;

    /** Below this confidence, an unconfirmed memory counts as "needs review" in the summary. */
    private static final double REVIEW_THRESHOLD = 0.85;
    /** Bounded scan size for summary computation — same discipline as the booster's LOOKBACK. */
    private static final int SUMMARY_SCAN_LIMIT = 300;

    private final CareerDecisionMemoryRepository memories;
    private final JobRepository jobs;
    private final CareerMemoryMetrics metrics;
    private final boolean enabled;

    public CareerMemoryService(CareerDecisionMemoryRepository memories, JobRepository jobs,
                               CareerMemoryMetrics metrics,
                               @Value("${career.memory.enabled:false}") boolean enabled) {
        this.memories = memories;
        this.jobs = jobs;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Append one memory. No-op when disabled (mirrors {@code RecommendationFeedbackService}'s
     * convention) so every caller can call this unconditionally and safely. Never throws —
     * callers are event listeners and best-effort side calls that must never break the flow
     * that triggered them.
     */
    @Transactional
    public void record(UUID userId, String decisionType, String category, String value, String reason,
                       BigDecimal confidence, String source, int importance, boolean aiGenerated,
                       UUID jobId, UUID correlationId, String workflowId, Instant expiresAt) {
        if (!enabled) return;
        metrics.recordExtractionAttempt();
        try {
            memories.save(CareerDecisionMemory.builder()
                    .userId(userId)
                    .decisionType(decisionType)
                    .category(category)
                    .value(value)
                    .reason(reason)
                    .confidence(confidence == null ? BigDecimal.ONE : confidence)
                    .source(source)
                    .importance((short) Math.max(1, Math.min(5, importance)))
                    .aiGenerated(aiGenerated)
                    .userConfirmed(false)
                    .expiresAt(expiresAt)
                    .jobId(jobId)
                    .correlationId(correlationId)
                    .workflowId(workflowId)
                    .usageCount(0)
                    .build());
            metrics.recordExtractionSuccess();
        } catch (Exception e) {
            metrics.recordExtractionFailure();
            log.warn("CAREER_MEMORY record failed type={} user={}: {}", decisionType, userId, e.toString());
        }
    }

    /**
     * Captures the highest-value signal in the system: an explicit user reason for
     * approving/rejecting/saving a job (Phase 2C's {@link RecommendationFeedback}). Called as an
     * additive side effect from {@code RecommendationFeedbackService.record} — never blocks or
     * fails that write.
     */
    @Transactional
    public void captureFeedback(RecommendationFeedback feedback) {
        if (!enabled || feedback == null) return;
        String decisionType = switch (feedback.getAction()) {
            case "REJECT" -> "JOB_REJECTED";
            case "APPROVE" -> "JOB_APPROVED";
            case "SAVE" -> "JOB_SAVED";
            case "IGNORE" -> "JOB_IGNORED";
            case "APPLY_LATER" -> "JOB_DEFERRED";
            default -> "JOB_FEEDBACK";
        };
        String value = describeJob(feedback.getJobId());
        // Explicit user action with a typed reason is the strongest possible signal — full
        // confidence, high importance, never AI-inferred.
        int importance = feedback.getReason() != null && !feedback.getReason().isBlank() ? 5 : 3;
        record(feedback.getUserId(), decisionType, "APPLICATION", value, feedback.getReason(),
                BigDecimal.ONE, "RECOMMENDATION_FEEDBACK", importance, false,
                feedback.getJobId(), null, null, null);
    }

    private String describeJob(UUID jobId) {
        if (jobId == null) return null;
        return jobs.findById(jobId)
                .map((Job j) -> {
                    String title = j.getTitle() == null ? "" : j.getTitle();
                    String company = j.getCompany() == null ? "" : j.getCompany();
                    return (title + (company.isBlank() ? "" : " @ " + company)).trim();
                })
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /**
     * Intelligent retrieval — the top {@code limit} memories for a category, ranked by
     * confidence × importance × recency, not a full-history dump (explicit requirement: the
     * Copilot must never be handed the whole ledger). Returns {@code List.of()} when disabled or
     * no memories exist, never {@code null}.
     */
    @Transactional(readOnly = true)
    public List<CareerDecisionMemory> relevantFor(UUID userId, String category, int limit) {
        if (!enabled) return List.of();
        long start = System.nanoTime();
        List<CareerDecisionMemory> candidates = memories.findActiveByUserIdAndCategory(userId, category, Instant.now());
        List<CareerDecisionMemory> ranked = rank(candidates, limit);
        metrics.recordRetrieval(System.nanoTime() - start);
        return ranked;
    }

    /** As {@link #relevantFor(UUID, String, int)} but across every category — for general-purpose skills. */
    @Transactional(readOnly = true)
    public List<CareerDecisionMemory> relevantFor(UUID userId, int limit) {
        if (!enabled) return List.of();
        long start = System.nanoTime();
        List<CareerDecisionMemory> candidates = memories.findActiveByUserId(userId, Instant.now());
        List<CareerDecisionMemory> ranked = rank(candidates, limit);
        metrics.recordRetrieval(System.nanoTime() - start);
        return ranked;
    }

    /** Full chronological timeline for a user (review area 7) — not ranked, not truncated. */
    @Transactional(readOnly = true)
    public List<CareerDecisionMemory> timelineFor(UUID userId) {
        return memories.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** One category's active memories, newest first — for the dashboard's per-category cards. */
    @Transactional(readOnly = true)
    public List<CareerDecisionMemory> forCategory(UUID userId, String category) {
        if (!enabled) return List.of();
        return memories.findActiveByUserIdAndCategory(userId, category, Instant.now());
    }

    /**
     * Phase 7.15.3 — the "AI Learned About You" header stats. Computed over a bounded scan of the
     * user's active memories (never a full unbounded aggregate query — same discipline as
     * {@code CareerMemoryBooster}'s LOOKBACK), so this stays cheap even for a long-lived account.
     *
     * <p>"Needs review": active, not yet user-confirmed, below {@value #REVIEW_THRESHOLD}
     * confidence. "Conflicting": an active memory in the same category as another active memory
     * whose decisionType suffix is the opposite polarity (e.g. TECHNOLOGY_POSITIVE vs
     * TECHNOLOGY_NEGATIVE for the same or a different value) — both real, computable signals over
     * existing data, not fabricated.
     */
    @Transactional(readOnly = true)
    public MemorySummary summaryFor(UUID userId) {
        if (!enabled) return MemorySummary.empty();
        List<CareerDecisionMemory> active = memories.findActiveByUserId(userId, Instant.now());
        if (active.size() > SUMMARY_SCAN_LIMIT) active = active.subList(0, SUMMARY_SCAN_LIMIT);

        int total = active.size();
        long verified = active.stream().filter(m -> Boolean.TRUE.equals(m.getUserConfirmed())).count();
        long needsReview = active.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getUserConfirmed()))
                .filter(m -> m.getConfidence() != null && m.getConfidence().doubleValue() < REVIEW_THRESHOLD)
                .count();
        long lowConfidence = active.stream()
                .filter(m -> m.getConfidence() != null && m.getConfidence().doubleValue() < 0.5)
                .count();

        java.util.Map<String, java.util.Set<String>> polaritiesByCategory = new java.util.HashMap<>();
        for (CareerDecisionMemory m : active) {
            String polarity = polaritySuffix(m.getDecisionType());
            if (polarity == null) continue;
            polaritiesByCategory.computeIfAbsent(m.getCategory(), k -> new java.util.HashSet<>()).add(polarity);
        }
        long conflicting = active.stream()
                .filter(m -> {
                    var polarities = polaritiesByCategory.get(m.getCategory());
                    return polarities != null && polarities.size() > 1;
                })
                .count();

        double avgConfidence = active.stream()
                .map(CareerDecisionMemory::getConfidence)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average().orElse(0.0);

        Instant lastUpdated = active.stream()
                .map(CareerDecisionMemory::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);

        return new MemorySummary(total, verified, needsReview, conflicting, lowConfidence, avgConfidence, lastUpdated);
    }

    private static String polaritySuffix(String decisionType) {
        if (decisionType == null) return null;
        if (decisionType.endsWith("_POSITIVE")) return "POSITIVE";
        if (decisionType.endsWith("_NEGATIVE")) return "NEGATIVE";
        return null;
    }

    /**
     * User confirms a memory is correct ("Approve Memory" / "Verify"). Ownership-checked. Never
     * touches any other row — append-only history is unaffected, only this row's
     * {@code userConfirmed} flag flips. Returns empty when disabled, not found, or not owned by
     * this user.
     */
    @Transactional
    public java.util.Optional<CareerDecisionMemory> confirm(UUID userId, UUID memoryId) {
        if (!enabled) return java.util.Optional.empty();
        return memories.findById(memoryId)
                .filter(m -> m.getUserId().equals(userId))
                .map(m -> {
                    m.setUserConfirmed(true);
                    return memories.save(m);
                });
    }

    /**
     * User says "forget this" ("Forget Memory" / "Reject"). This is a soft-expire
     * ({@code expiresAt = now}), NEVER a hard delete — the append-only design means history is
     * preserved for audit even when the AI stops actively using a memory; it simply drops out of
     * {@link #relevantFor} / {@link #forCategory} / the {@code CareerMemoryBooster} the moment it
     * expires. Ownership-checked. Returns empty when disabled, not found, or not owned.
     */
    @Transactional
    public java.util.Optional<CareerDecisionMemory> forget(UUID userId, UUID memoryId) {
        if (!enabled) return java.util.Optional.empty();
        return memories.findById(memoryId)
                .filter(m -> m.getUserId().equals(userId))
                .map(m -> {
                    m.setExpiresAt(Instant.now());
                    return memories.save(m);
                });
    }

    /**
     * "Edit Memory" — a user directly telling the AI a preference, e.g. correcting a country or
     * salary. Same {@link CareerDecisionMemory} entity and {@link CareerDecisionMemoryRepository}
     * as {@link #record} — no new persistence path — but returns the saved row synchronously
     * (unlike {@code record}'s fire-and-forget void contract) so the dashboard can show it
     * immediately, and it's stamped {@code aiGenerated=false, userConfirmed=true, confidence=1.0,
     * importance=5} — an explicit user statement is the strongest possible signal, same
     * convention {@code captureFeedback} already uses for a typed rejection reason. This is a NEW
     * row, never an update to an existing one — append-only history is preserved exactly like
     * every other write path; a stale prior value simply ranks lower going forward via the
     * existing freshness-weighted scoring in {@link #relevantFor}.
     */
    @Transactional
    public java.util.Optional<CareerDecisionMemory> recordUserEdit(UUID userId, String category, String value, String reason) {
        if (!enabled || category == null || value == null || value.isBlank()) return java.util.Optional.empty();
        String normalizedCategory = category.toUpperCase();
        CareerDecisionMemory saved = memories.save(CareerDecisionMemory.builder()
                .userId(userId)
                .decisionType(normalizedCategory + "_POSITIVE")
                .category(normalizedCategory)
                .value(value)
                .reason(reason)
                .confidence(BigDecimal.ONE)
                .source("USER_EDIT")
                .importance((short) 5)
                .aiGenerated(false)
                .userConfirmed(true)
                .usageCount(0)
                .build());
        return java.util.Optional.of(saved);
    }

    /** Header stats for the AI Memory Dashboard. */
    public record MemorySummary(int totalMemories, long verifiedCount, long needsReviewCount,
                                long conflictingCount, long lowConfidenceCount,
                                double averageConfidence, Instant lastUpdated) {
        public static MemorySummary empty() {
            return new MemorySummary(0, 0, 0, 0, 0, 0.0, null);
        }
    }

    /**
     * Bumps usage_count/last_used_at for exactly the memories that were actually surfaced to the
     * Copilot this turn. Deliberately {@code REQUIRES_NEW}: the caller (e.g.
     * {@code CareerContextRetriever.getRelevantMemories}) runs inside a {@code readOnly=true}
     * transaction for the retrieval itself, and this write must not join that transaction — a
     * fresh one keeps the read path's read-only guarantee intact and this call independently
     * best-effort. Never throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(List<UUID> memoryIds) {
        if (!enabled || memoryIds == null || memoryIds.isEmpty()) return;
        try {
            memories.bumpUsage(memoryIds, Instant.now());
        } catch (Exception e) {
            log.debug("CAREER_MEMORY usage bump failed: {}", e.toString());
        }
    }

    private List<CareerDecisionMemory> rank(List<CareerDecisionMemory> candidates, int limit) {
        Instant now = Instant.now();
        return candidates.stream()
                .sorted(Comparator.comparingDouble((CareerDecisionMemory m) -> score(m, now)).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    private double score(CareerDecisionMemory m, Instant now) {
        double confidence = m.getConfidence() == null ? 1.0 : m.getConfidence().doubleValue();
        double importance = (m.getImportance() == null ? 3 : m.getImportance()) / 5.0;
        double ageDays = Math.max(0, java.time.Duration.between(m.getCreatedAt(), now).toHours() / 24.0);
        double freshness = 1.0 / (1.0 + ageDays / FRESHNESS_DECAY_DAYS);
        return confidence * importance * freshness;
    }
}
