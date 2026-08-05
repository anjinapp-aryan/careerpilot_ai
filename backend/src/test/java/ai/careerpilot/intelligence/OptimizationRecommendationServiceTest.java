package ai.careerpilot.intelligence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 13B — recommendations. Every test here is ultimately about one property: a recommendation
 * either carries a real citation or is not emitted.
 */
class OptimizationRecommendationServiceTest {

    private OptimizationRecommendationService service(boolean enabled) {
        return new OptimizationRecommendationService(mock(ProductionIntelligenceService.class), enabled);
    }

    private static Evidence evidence(int sample) {
        return Evidence.of("SuccessPatternEngine", sample, Map.of("interviews", sample / 3), Instant.now());
    }

    private static ProductionOptimizationSnapshot.DimensionFinding finding(String dim, String key,
                                                                          int apps, double rate) {
        return new ProductionOptimizationSnapshot.DimensionFinding(dim, key, apps, apps / 3, apps / 10,
                rate, evidence(apps));
    }

    private static ProductionOptimizationSnapshot snapshot(
            ProductionOptimizationSnapshot.ResumeIntelligence resume,
            List<ProductionOptimizationSnapshot.DimensionFinding> countries,
            ProductionOptimizationSnapshot.AtsIntelligence ats) {
        return new ProductionOptimizationSnapshot(Instant.now(), resume, countries, List.of(),
                List.of(), ats, List.of());
    }

    private static ProductionOptimizationSnapshot.ResumeIntelligence resume(String version) {
        return new ProductionOptimizationSnapshot.ResumeIntelligence(version, 312, 92, 18,
                29.5, 5.8, 3, Evidence.of("ResumeLearningService", 312,
                        Map.of("interviews", 92), Instant.now()), "best of 3");
    }

    // ── the core guarantee ──

    @Test
    void everyRecommendationCarriesACitation() {
        var recommendations = service(true).recommend(snapshot(resume("v8"),
                List.of(finding("LOCATION", "Germany", 60, 31.0)), null));

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations).allSatisfy(r -> {
            assertThat(r.evidence()).isNotNull();
            assertThat(r.evidence().isActionable()).isTrue();
            assertThat(r.render()).contains("source:");
        });
    }

    @Test
    void aResumeTheSnapshotDeclinedToRecommendIsNeverPromotedHere() {
        // The snapshot already judged the evidence too thin. This service must not overrule it.
        var thin = new ProductionOptimizationSnapshot.ResumeIntelligence(null, 3, 1, 0,
                33.3, 0.0, 1, Evidence.of("ResumeLearningService", 3, Map.of(), Instant.now()),
                "below the floor");

        assertThat(service(true).recommend(snapshot(thin, List.of(), null))).isEmpty();
    }

    @Test
    void disabledEmitsNothing() {
        assertThat(service(false).recommend(snapshot(resume("v8"), List.of(), null))).isEmpty();
    }

    @Test
    void anEmptySnapshotEmitsNothing() {
        assertThat(service(true).recommend(ProductionOptimizationSnapshot.empty("no data"))).isEmpty();
        assertThat(service(true).recommend((ProductionOptimizationSnapshot) null)).isEmpty();
    }

    // ── comparative claims ──

    @Test
    void aMaterialLeadIsQuotedWithBothRates() {
        var recommendations = service(true).recommend(snapshot(null, List.of(
                finding("LOCATION", "Germany", 60, 31.0),
                finding("LOCATION", "Netherlands", 40, 12.0)), null));

        var country = recommendations.stream().filter(r -> "COUNTRY".equals(r.category())).findFirst().orElseThrow();
        assertThat(country.action()).contains("Germany");
        assertThat(country.rationale()).contains("31%").contains("Netherlands").contains("12%");
    }

    /**
     * The tempting error: "+2% ahead" reads far better than "based on 60 applications", so a
     * marginal lead must be labelled as marginal rather than dressed up.
     */
    @Test
    void aMarginalLeadIsLabelledAsMarginalRatherThanDressedUp() {
        var recommendations = service(true).recommend(snapshot(null, List.of(
                finding("LOCATION", "Germany", 60, 31.0),
                finding("LOCATION", "Netherlands", 40, 29.0)), null));

        var country = recommendations.stream().filter(r -> "COUNTRY".equals(r.category())).findFirst().orElseThrow();
        assertThat(country.rationale()).contains("only marginally ahead");
    }

    @Test
    void aSingleEntryIsStatedFlatlyWithNoInventedComparison() {
        var recommendations = service(true).recommend(snapshot(null,
                List.of(finding("LOCATION", "Germany", 60, 31.0)), null));

        var country = recommendations.get(0);
        assertThat(country.rationale()).contains("60 applications");
        assertThat(country.rationale()).doesNotContain("ahead of");
    }

    // ── ATS ──

    @Test
    void anAtsRecommendationIsOnlyMadeWhenThereIsARealSpread() {
        var narrow = new ProductionOptimizationSnapshot.AtsIntelligence("GREENHOUSE", 96,
                "LEVER", 94, List.of("GREENHOUSE", "LEVER"), evidence(20), "caveat");
        assertThat(service(true).recommend(snapshot(null, List.of(), narrow))).isEmpty();

        var wide = new ProductionOptimizationSnapshot.AtsIntelligence("GREENHOUSE", 98,
                "WORKDAY", 62, List.of("GREENHOUSE"), evidence(20), "measures automation only");
        assertThat(service(true).recommend(snapshot(null, List.of(), wide))).isNotEmpty();
    }

    @Test
    void aSingleValidatedAtsProducesNoRecommendation() {
        // "Our best ATS is the only one we tested" is not a recommendation.
        var single = new ProductionOptimizationSnapshot.AtsIntelligence("GREENHOUSE", 98,
                null, null, List.of("GREENHOUSE"), evidence(20), "caveat");
        assertThat(service(true).recommend(snapshot(null, List.of(), single))).isEmpty();
    }

    @Test
    void theAtsCaveatSurvivesIntoTheRationale() {
        var wide = new ProductionOptimizationSnapshot.AtsIntelligence("GREENHOUSE", 98,
                "WORKDAY", 62, List.of("GREENHOUSE"), evidence(20),
                "Measures form-automation readiness only.");
        var ats = service(true).recommend(snapshot(null, List.of(), wide)).get(0);
        assertThat(ats.rationale()).contains("Measures form-automation readiness only.");
    }

    // ── ordering and rendering ──

    @Test
    void resumeOutranksGeographyWhichOutranksAts() {
        var wide = new ProductionOptimizationSnapshot.AtsIntelligence("GREENHOUSE", 98,
                "WORKDAY", 62, List.of(), evidence(20), "caveat");
        var recommendations = service(true).recommend(snapshot(resume("v8"),
                List.of(finding("LOCATION", "Germany", 60, 31.0)), wide));

        assertThat(recommendations).extracting(OptimizationRecommendationService.Recommendation::category)
                .containsExactly("RESUME", "COUNTRY", "ATS");
        assertThat(recommendations).isSortedAccordingTo(
                java.util.Comparator.comparingInt(OptimizationRecommendationService.Recommendation::priority));
    }

    @Test
    void renderingNothingSaysNoVerifiedDataRatherThanFallingSilent() {
        // Silence would read as "nothing to improve", which is a different and false claim.
        assertThat(service(true).render(List.of())).isEqualTo("No verified data available.");
        assertThat(service(true).render(null)).isEqualTo("No verified data available.");
    }

    @Test
    void aRenderedLineIsSelfContainedAndVerifiable() {
        var line = service(true).recommend(snapshot(resume("v8"), List.of(), null)).get(0).render();
        assertThat(line).contains("resume version v8")
                .contains("92 interview").contains("18 offer").contains("312 application")
                .contains("ResumeLearningService");
    }

    @Test
    void singularAndPluralCountsReadCorrectly() {
        var one = new ProductionOptimizationSnapshot.ResumeIntelligence("v1", 6, 1, 1, 16.6, 16.6, 1,
                Evidence.of("ResumeLearningService", 6, Map.of(), Instant.now()), "only version");
        var line = service(true).recommend(snapshot(one, List.of(), null)).get(0).rationale();
        assertThat(line).contains("1 interview and 1 offer").doesNotContain("1 interviews");
    }
}
