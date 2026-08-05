package ai.careerpilot.intelligence;

import ai.careerpilot.domain.Job;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13C — the job-ranking adjustment. Two properties carry this class: it stays inside its
 * ±10 budget whatever the evidence says, and an <em>absence</em> of evidence never becomes a
 * penalty.
 */
class ProductionIntelligenceBoosterTest {

    private static ProductionIntelligenceBooster booster(boolean enabled) {
        return new ProductionIntelligenceBooster(enabled);
    }

    private static ProductionOptimizationSnapshot.DimensionFinding finding(String dim, String key, double rate) {
        return new ProductionOptimizationSnapshot.DimensionFinding(dim, key, 60, 18, 4, rate,
                Evidence.of("SuccessPatternEngine", 60, Map.of("interviews", 18), Instant.now()));
    }

    private static ProductionOptimizationSnapshot snapshot(
            List<ProductionOptimizationSnapshot.DimensionFinding> countries,
            List<ProductionOptimizationSnapshot.DimensionFinding> companies,
            List<ProductionOptimizationSnapshot.DimensionFinding> skills) {
        return new ProductionOptimizationSnapshot(Instant.now(), null, countries, companies,
                skills, null, List.of());
    }

    private static Job job(String country, String company, String skills) {
        return Job.builder().title("Backend Engineer").country(country)
                .company(company).skills(skills).build();
    }

    // ── the bounds ──

    @Test
    void theAdjustmentNeverExceedsTenPointsInEitherDirection() {
        // Every dimension maximally positive at once.
        var strong = snapshot(
                List.of(finding("LOCATION", "Germany", 100.0)),
                List.of(finding("COMPANY", "Acme", 100.0)),
                List.of(finding("SKILL", "java", 100.0)));
        assertThat(booster(true).computeBoost(strong, job("Germany", "Acme", "java, spring")))
                .isLessThanOrEqualTo(ProductionIntelligenceBooster.MAX_TOTAL);

        var weak = snapshot(
                List.of(finding("LOCATION", "Germany", 0.0)),
                List.of(finding("COMPANY", "Acme", 0.0)),
                List.of(finding("SKILL", "java", 0.0)));
        assertThat(booster(true).computeBoost(weak, job("Germany", "Acme", "java, spring")))
                .isGreaterThanOrEqualTo(-ProductionIntelligenceBooster.MAX_TOTAL);
    }

    @Test
    void aStrongSignalRaisesAndAWeakSignalLowersTheScore() {
        var good = snapshot(List.of(finding("LOCATION", "Germany", 45.0)), List.of(), List.of());
        var bad = snapshot(List.of(finding("LOCATION", "Germany", 4.0)), List.of(), List.of());

        assertThat(booster(true).computeBoost(good, job("Germany", "Acme", ""))).isPositive();
        assertThat(booster(true).computeBoost(bad, job("Germany", "Acme", ""))).isNegative();
    }

    /**
     * The property that stops this quietly suppressing an unexplored market — which is exactly the
     * market a candidate most needs surfaced.
     */
    @Test
    void anAttributeWithNoEvidenceContributesZeroRatherThanAPenalty() {
        var snapshot = snapshot(List.of(finding("LOCATION", "Germany", 45.0)), List.of(), List.of());
        assertThat(booster(true).computeBoost(snapshot, job("Japan", "Unknown Co", "rust"))).isZero();
    }

    @Test
    void aNeutralRateProducesNoAdjustment() {
        var snapshot = snapshot(List.of(finding("LOCATION", "Germany", 20.0)), List.of(), List.of());
        assertThat(booster(true).computeBoost(snapshot, job("Germany", "Acme", ""))).isZero();
    }

    // ── gating and safety ──

    @Test
    void disabledAlwaysReturnsZero() {
        var strong = snapshot(List.of(finding("LOCATION", "Germany", 100.0)), List.of(), List.of());
        assertThat(booster(false).isActive()).isFalse();
        assertThat(booster(false).computeBoost(strong, job("Germany", "Acme", "java"))).isZero();
    }

    @Test
    void aNullOrEmptySnapshotProducesNoAdjustment() {
        assertThat(booster(true).computeBoost(null, job("Germany", "Acme", "java"))).isZero();
        assertThat(booster(true).computeBoost(ProductionOptimizationSnapshot.empty("none"),
                job("Germany", "Acme", "java"))).isZero();
    }

    @Test
    void aNullJobOrBlankAttributesAreHandledWithoutThrowing() {
        var snapshot = snapshot(List.of(finding("LOCATION", "Germany", 45.0)), List.of(), List.of());
        assertThat(booster(true).computeBoost(snapshot, null)).isZero();
        assertThat(booster(true).computeBoost(snapshot, Job.builder().title("Eng").build())).isZero();
    }

    // ── matching behaviour ──

    @Test
    void locationIsUsedWhenAJobCarriesNoExplicitCountry() {
        // Discovered jobs frequently carry only a location string, and SuccessPattern's LOCATION
        // dimension is populated from the same kind of free text.
        var snapshot = snapshot(List.of(finding("LOCATION", "Germany", 45.0)), List.of(), List.of());
        Job job = Job.builder().title("Eng").location("Berlin, Germany").build();
        assertThat(booster(true).computeBoost(snapshot, job)).isPositive();
    }

    @Test
    void companyNamesMatchAcrossSuffixVariations() {
        var snapshot = snapshot(List.of(), List.of(finding("COMPANY", "Acme", 45.0)), List.of());
        assertThat(booster(true).computeBoost(snapshot, job(null, "Acme Inc", ""))).isPositive();
    }

    /**
     * Summing skill matches would let a job listing ten technologies out-rank one listing the two
     * that actually matter.
     */
    @Test
    void skillsUseTheStrongestSingleMatchRatherThanASum() {
        var snapshot = snapshot(List.of(), List.of(), List.of(
                finding("SKILL", "java", 45.0),
                finding("SKILL", "spring", 45.0),
                finding("SKILL", "aws", 45.0)));

        int many = booster(true).computeBoost(snapshot, job(null, null, "java, spring, aws"));
        int one = booster(true).computeBoost(snapshot, job(null, null, "java"));

        assertThat(many).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(ProductionIntelligenceBooster.MAX_SKILL);
    }

    @Test
    void skillsAreAlsoMatchedFromTheJobTitle() {
        var snapshot = snapshot(List.of(), List.of(), List.of(finding("SKILL", "java", 45.0)));
        Job job = Job.builder().title("Senior Java Engineer").build();
        assertThat(booster(true).computeBoost(snapshot, job)).isPositive();
    }

    @Test
    void eachDimensionStaysWithinItsOwnCap() {
        var countryOnly = snapshot(List.of(finding("LOCATION", "Germany", 100.0)), List.of(), List.of());
        assertThat(booster(true).computeBoost(countryOnly, job("Germany", null, "")))
                .isLessThanOrEqualTo(ProductionIntelligenceBooster.MAX_COUNTRY);

        var companyOnly = snapshot(List.of(), List.of(finding("COMPANY", "Acme", 100.0)), List.of());
        assertThat(booster(true).computeBoost(companyOnly, job(null, "Acme", "")))
                .isLessThanOrEqualTo(ProductionIntelligenceBooster.MAX_COMPANY);
    }

    @Test
    void anExtremeRateSaturatesRatherThanDominating() {
        // 40% and 400% both sit at the cap: past twice neutral, extra rate buys nothing.
        var high = snapshot(List.of(finding("LOCATION", "Germany", 40.0)), List.of(), List.of());
        var absurd = snapshot(List.of(finding("LOCATION", "Germany", 400.0)), List.of(), List.of());
        assertThat(booster(true).computeBoost(high, job("Germany", null, "")))
                .isEqualTo(booster(true).computeBoost(absurd, job("Germany", null, "")));
    }

    @Test
    void theExplanationNeverInventsAFigure() {
        var snapshot = snapshot(List.of(finding("LOCATION", "Germany", 45.0)), List.of(), List.of());
        java.util.UUID userId = java.util.UUID.randomUUID();

        assertThat(booster(true).explain(snapshot, job("Germany", null, ""), userId))
                .contains("adjusted this match by");
        assertThat(booster(true).explain(snapshot, job("Japan", null, ""), userId))
                .isEqualTo("no production-evidence adjustment applied");
    }
}
