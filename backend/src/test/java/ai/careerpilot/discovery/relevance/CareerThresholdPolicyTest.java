package ai.careerpilot.discovery.relevance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 3B.1.1 — threshold policy: legacy hard cutoff when off, per-feed bands when on. */
class CareerThresholdPolicyTest {

    @Test
    void softThresholdsOffPreservesLegacyHardCutoffForDomesticAndInternational() {
        CareerThresholdPolicy policy = new CareerThresholdPolicy(false, 85, 60, 60, 60);

        assertFalse(policy.isVisibleForScope("domestic", 69));
        assertTrue(policy.isVisibleForScope("domestic", 70));
        assertFalse(policy.isVisibleForScope("international", 69));
        assertTrue(policy.isVisibleForScope("international", 70));
    }

    @Test
    void softThresholdsOnUsesConfiguredPerScopeBands() {
        CareerThresholdPolicy policy = new CareerThresholdPolicy(true, 85, 60, 60, 60);

        assertFalse(policy.isVisibleForScope("domestic", 59));
        assertTrue(policy.isVisibleForScope("domestic", 60));
        assertFalse(policy.isVisibleForScope("international", 59));
        assertTrue(policy.isVisibleForScope("international", 60));
    }

    @Test
    void softThresholdsOnReproducesSpecExamples() {
        CareerThresholdPolicy policy = new CareerThresholdPolicy(true, 85, 60, 60, 60);

        // Domestic spec examples: 92/84/74/65 visible, 59 hidden.
        for (int score : new int[]{92, 84, 74, 65}) {
            assertTrue(policy.isVisibleForScope("domestic", score), "expected " + score + " visible");
        }
        assertFalse(policy.isVisibleForScope("domestic", 59));

        // International spec examples: 95/81/71/63 visible, 59 hidden.
        for (int score : new int[]{95, 81, 71, 63}) {
            assertTrue(policy.isVisibleForScope("international", score), "expected " + score + " visible");
        }
        assertFalse(policy.isVisibleForScope("international", 59));
    }

    @Test
    void recommendedStaysCuratedRegardlessOfSoftThresholdsFlag() {
        CareerThresholdPolicy offPolicy = new CareerThresholdPolicy(false, 85, 60, 60, 60);
        CareerThresholdPolicy onPolicy = new CareerThresholdPolicy(true, 85, 60, 60, 60);

        assertFalse(offPolicy.isVisibleForRecommended(84));
        assertTrue(offPolicy.isVisibleForRecommended(85));
        assertFalse(onPolicy.isVisibleForRecommended(84));
        assertTrue(onPolicy.isVisibleForRecommended(85));
    }

    @Test
    void browseIsAlwaysVisible() {
        CareerThresholdPolicy policy = new CareerThresholdPolicy(true, 85, 60, 60, 60);
        assertTrue(policy.isVisibleForBrowse(0));
        assertTrue(policy.isVisibleForBrowse(100));
    }

    @Test
    void unknownScopeFallsBackToInternationalThreshold() {
        CareerThresholdPolicy policy = new CareerThresholdPolicy(true, 85, 60, 60, 60);
        assertTrue(policy.isVisibleForScope("anything-else", 60));
        assertFalse(policy.isVisibleForScope("anything-else", 59));
    }
}
