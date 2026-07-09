package ai.careerpilot.discovery.relevance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 3B.1.1 — configurable per-feed visibility thresholds, replacing the single hard-coded
 * {@code score < 70 => reject} cutoff with soft, per-feed bands. Gated by
 * {@code career.soft-thresholds.enabled} (default {@code false}): while off, {@link
 * #isVisibleForScope} reproduces the exact pre-3B.1.1 behavior ({@code score >= 70}), so existing
 * Domestic/International callers see zero behavior change until the flag is explicitly flipped.
 */
@Component
public class CareerThresholdPolicy {

    /** The hard cutoff Domestic/International used before this phase — preserved when the flag is off. */
    public static final int LEGACY_FEED_THRESHOLD = CareerRelevanceScore.FEED_THRESHOLD;

    private final boolean softThresholdsEnabled;
    private final int recommendedThreshold;
    private final int domesticThreshold;
    private final int internationalThreshold;
    private final int hiddenThreshold;

    public CareerThresholdPolicy(
            @Value("${career.soft-thresholds.enabled:false}") boolean softThresholdsEnabled,
            @Value("${career.relevance.thresholds.recommended:85}") int recommendedThreshold,
            @Value("${career.relevance.thresholds.domestic:60}") int domesticThreshold,
            @Value("${career.relevance.thresholds.international:60}") int internationalThreshold,
            @Value("${career.relevance.thresholds.hidden:60}") int hiddenThreshold) {
        this.softThresholdsEnabled = softThresholdsEnabled;
        this.recommendedThreshold = recommendedThreshold;
        this.domesticThreshold = domesticThreshold;
        this.internationalThreshold = internationalThreshold;
        this.hiddenThreshold = hiddenThreshold;
    }

    public boolean isSoftThresholdsEnabled() { return softThresholdsEnabled; }

    public int recommendedThreshold() { return recommendedThreshold; }
    public int domesticThreshold() { return domesticThreshold; }
    public int internationalThreshold() { return internationalThreshold; }
    public int browseThreshold() { return 0; }
    public int hiddenThreshold() { return hiddenThreshold; }

    /**
     * Visibility for the Domestic/International feeds. {@code scope} is {@code "domestic"} or
     * {@code "international"}; anything else falls back to the international threshold.
     */
    public boolean isVisibleForScope(String scope, int score) {
        if (!softThresholdsEnabled) {
            return score >= LEGACY_FEED_THRESHOLD;
        }
        int threshold = "domestic".equals(scope) ? domesticThreshold : internationalThreshold;
        return score >= threshold;
    }

    /** Recommended stays highly curated regardless of the soft-thresholds flag (spec: score >= 85 default). */
    public boolean isVisibleForRecommended(int score) {
        return score >= recommendedThreshold;
    }

    /** Browse is never filtered — provided for API completeness with the other three feeds. */
    public boolean isVisibleForBrowse(int score) {
        return score >= browseThreshold();
    }
}
