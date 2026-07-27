package ai.careerpilot.career.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Phase 11.5 — the default {@link CareerRecommendationEngine}. Ranks by {@link
 * CareerAlertSeverity} (CRITICAL first), then most-recently-detected first, capped at {@code
 * limit} so a user's insights feed stays scannable rather than an unbounded dump.
 */
public class DefaultCareerRecommendationEngine implements CareerRecommendationEngine {

    private static final Map<CareerAlertSeverity, Integer> SEVERITY_RANK = Map.of(
            CareerAlertSeverity.CRITICAL, 0,
            CareerAlertSeverity.HIGH, 1,
            CareerAlertSeverity.MEDIUM, 2,
            CareerAlertSeverity.LOW, 3,
            CareerAlertSeverity.INFO, 4);

    @Override
    public List<CareerAlert> prioritize(List<CareerAlert> alerts, int limit) {
        return alerts.stream()
                .sorted(Comparator
                        .comparingInt((CareerAlert a) -> SEVERITY_RANK.get(a.severity()))
                        .thenComparing(CareerAlert::detectedAt, Comparator.reverseOrder()))
                .limit(Math.max(0, limit))
                .toList();
    }
}
