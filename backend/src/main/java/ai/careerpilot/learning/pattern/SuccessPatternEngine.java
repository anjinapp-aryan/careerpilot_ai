package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6.2 — recomputes {@link SuccessPattern} rows for every dimension a triggering
 * {@link LearningEvent} touches. Delegates key extraction/matching to the 7 injected
 * {@link SuccessDimensionAnalyzer} beans and the actual math to {@link PatternStatsCalculator}.
 * Recomputes from the user's full event history each time (idempotent upsert) rather than
 * incrementally bookkeeping counters, so a re-run can never double-count.
 */
@Component
public class SuccessPatternEngine {

    private final List<SuccessDimensionAnalyzer> analyzers;
    private final LearningEventRepository events;
    private final SuccessPatternRepository patterns;
    private final boolean enabled;

    public SuccessPatternEngine(List<SuccessDimensionAnalyzer> analyzers,
                                LearningEventRepository events,
                                SuccessPatternRepository patterns,
                                @Value("${learning.success-pattern.enabled:false}") boolean enabled) {
        this.analyzers = analyzers;
        this.events = events;
        this.patterns = patterns;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public void analyze(LearningEvent triggeringEvent) {
        if (!enabled) return;
        UUID userId = triggeringEvent.getUserId();
        List<LearningEvent> history = events.findByUserIdOrderByCreatedAtDesc(userId);
        for (SuccessDimensionAnalyzer analyzer : analyzers) {
            for (String key : analyzer.extractKeys(triggeringEvent)) {
                recomputeOne(userId, analyzer, key, history);
            }
        }
    }

    private void recomputeOne(UUID userId, SuccessDimensionAnalyzer analyzer, String key, List<LearningEvent> history) {
        List<LearningEvent> matching = history.stream().filter(e -> analyzer.matches(e, key)).toList();
        PatternStatsCalculator.SuccessStats stats = PatternStatsCalculator.success(matching);

        SuccessPattern row = patterns.findByUserIdAndDimensionAndDimensionKey(userId, analyzer.dimension(), key)
                .orElseGet(() -> SuccessPattern.builder().userId(userId).dimension(analyzer.dimension()).dimensionKey(key).build());
        row.setApplications(stats.applications());
        row.setInterviews(stats.interviews());
        row.setOffers(stats.offers());
        row.setSuccessRate(stats.successRate());
        row.setComputedAt(Instant.now());
        patterns.save(row);
    }
}
