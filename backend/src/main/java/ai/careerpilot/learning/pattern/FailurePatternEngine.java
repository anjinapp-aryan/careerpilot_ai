package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.repo.FailurePatternRepository;
import ai.careerpilot.repo.LearningEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 6.3 — recomputes {@link FailurePattern} rows, mirroring {@link SuccessPatternEngine}. */
@Component
public class FailurePatternEngine {

    private final List<FailureDimensionAnalyzer> analyzers;
    private final LearningEventRepository events;
    private final FailurePatternRepository patterns;
    private final boolean enabled;

    public FailurePatternEngine(List<FailureDimensionAnalyzer> analyzers,
                                LearningEventRepository events,
                                FailurePatternRepository patterns,
                                @Value("${learning.failure-pattern.enabled:false}") boolean enabled) {
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
        for (FailureDimensionAnalyzer analyzer : analyzers) {
            for (String key : analyzer.extractKeys(triggeringEvent)) {
                recomputeOne(userId, analyzer, key, history);
            }
        }
    }

    private void recomputeOne(UUID userId, FailureDimensionAnalyzer analyzer, String key, List<LearningEvent> history) {
        List<LearningEvent> matching = history.stream().filter(e -> analyzer.matches(e, key)).toList();
        PatternStatsCalculator.FailureStats stats = PatternStatsCalculator.failure(matching);

        FailurePattern row = patterns.findByUserIdAndDimensionAndDimensionKey(userId, analyzer.dimension(), key)
                .orElseGet(() -> FailurePattern.builder().userId(userId).dimension(analyzer.dimension()).dimensionKey(key).build());
        row.setApplications(stats.applications());
        row.setResponses(stats.responses());
        row.setFailureRate(stats.failureRate());
        row.setRecommendedPenalty(stats.recommendedPenalty());
        row.setComputedAt(Instant.now());
        patterns.save(row);
    }
}
