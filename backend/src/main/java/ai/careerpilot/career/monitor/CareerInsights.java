package ai.careerpilot.career.monitor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.5 — {@link CareerMonitor}'s full output for one run: every alert detected this run
 * ({@code alerts}, for transparency/debugging) and the subset actually worth surfacing to the
 * user ({@code recommendations} — deduplicated against {@link CareerTimeline}'s recent history
 * and prioritized by {@link CareerRecommendationEngine}).
 */
public record CareerInsights(UUID userId, List<CareerAlert> alerts, List<CareerAlert> recommendations,
                              Instant generatedAt, String summary) {

    public static CareerInsights empty(UUID userId, String summary) {
        return new CareerInsights(userId, List.of(), List.of(), Instant.now(), summary);
    }
}
