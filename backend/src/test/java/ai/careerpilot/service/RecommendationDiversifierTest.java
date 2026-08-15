package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJob;
import ai.careerpilot.domain.Job;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Global Job Discovery Expansion — {@link RecommendationDiversifier}. The two load-bearing rules
 * under test: relevance bands are never crossed (a materially better job is never displaced by a
 * materially worse one purely for country diversity), and a single-country result set is
 * untouched.
 */
class RecommendationDiversifierTest {

    private static RecommendedJob job(String country, int score) {
        Job j = Job.builder().title("Senior Engineer").company("Acme").country(country).build();
        return new RecommendedJob(j, score, List.of(), List.of());
    }

    @Test
    void flagOffReturnsTheInputListUnchanged() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(false, false);
        List<RecommendedJob> ranked = List.of(job("Germany", 94), job("Germany", 93), job("Germany", 92));

        assertThat(diversifier.diversify(ranked)).isSameAs(ranked);
    }

    @Test
    void aHighScoringJobIsNeverDisplacedByAMaterallyLowerScoringOtherCountryJob() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, false);
        RecommendedJob top = job("Germany", 94);
        RecommendedJob low = job("United Arab Emirates", 55);
        List<RecommendedJob> ranked = List.of(top, low);

        List<RecommendedJob> result = diversifier.diversify(ranked);

        // 94 (band 90-94) and 55 (band 55-59) are different bands — order between bands is
        // preserved untouched regardless of diversification.
        assertThat(result.get(0)).isEqualTo(top);
        assertThat(result.get(1)).isEqualTo(low);
    }

    @Test
    void withinASimilarScoreBandCountriesAreInterleavedRoundRobin() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, false);
        // All in the 90-94 band.
        RecommendedJob de1 = job("Germany", 94);
        RecommendedJob de2 = job("Germany", 93);
        RecommendedJob de3 = job("Germany", 92);
        RecommendedJob nl1 = job("Netherlands", 91);
        List<RecommendedJob> ranked = List.of(de1, de2, de3, nl1);

        List<RecommendedJob> result = diversifier.diversify(ranked);

        // Germany's highest scorer still leads, but the Netherlands entry is pulled forward
        // (round-robin) rather than being pushed to the very end behind all three Germany jobs.
        assertThat(result.get(0)).isEqualTo(de1);
        assertThat(result.get(1)).isEqualTo(nl1);
        assertThat(result).containsExactlyInAnyOrder(de1, de2, de3, nl1);
    }

    @Test
    void singleCountryBandIsLeftInOriginalOrder() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, false);
        List<RecommendedJob> ranked = List.of(job("Germany", 94), job("Germany", 93), job("Germany", 92));

        assertThat(diversifier.diversify(ranked)).containsExactlyElementsOf(ranked);
    }

    @Test
    void eachCountrysOwnRelativeOrderIsPreservedWithinTheInterleave() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, false);
        RecommendedJob de1 = job("Germany", 94);
        RecommendedJob de2 = job("Germany", 92);
        RecommendedJob nl1 = job("Netherlands", 93);
        RecommendedJob nl2 = job("Netherlands", 90);
        List<RecommendedJob> ranked = List.of(de1, nl1, de2, nl2);

        List<RecommendedJob> result = diversifier.diversify(ranked);

        List<RecommendedJob> germanyOnly = result.stream()
                .filter(r -> "Germany".equals(r.job().getCountry())).collect(Collectors.toList());
        List<RecommendedJob> nlOnly = result.stream()
                .filter(r -> "Netherlands".equals(r.job().getCountry())).collect(Collectors.toList());

        assertThat(germanyOnly).containsExactly(de1, de2);
        assertThat(nlOnly).containsExactly(nl1, nl2);
    }

    @Test
    void fewerThanTwoJobsIsUnchanged() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, false);
        List<RecommendedJob> single = List.of(job("Germany", 94));

        assertThat(diversifier.diversify(single)).isSameAs(single);
        assertThat(diversifier.diversify(null)).isNull();
    }

    // ── International Job Discovery Phase 2 — quota-weighted pull order ──

    @Test
    void quotaWeightingNeverCrossesScoreBandsEitherThe94GermanyStaysAheadOf55Usa() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, true);
        RecommendedJob top = job("Germany", 94);
        RecommendedJob low = job("United States", 55);

        List<RecommendedJob> result = diversifier.diversify(List.of(top, low));

        assertThat(result.get(0)).isEqualTo(top);
        assertThat(result.get(1)).isEqualTo(low);
    }

    @Test
    void quotaWeightingPullsTheHigherSoftQuotaCountryFirstWithinABand() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, true);
        // Same band (90-94). Germany's soft quota (25) is higher than Poland's (9), so Germany's
        // representative is pulled before Poland's even though Poland was scored marginally higher
        // — this is exactly the "soft guidance changes pull order, never the band" contract.
        RecommendedJob poland = job("Poland", 93);
        RecommendedJob germany = job("Germany", 92);
        List<RecommendedJob> ranked = List.of(poland, germany);

        List<RecommendedJob> result = diversifier.diversify(ranked);

        assertThat(result.get(0)).isEqualTo(germany);
        assertThat(result.get(1)).isEqualTo(poland);
    }

    @Test
    void quotaWeightingOffFallsBackToPlainInsertionOrderRoundRobin() {
        // Same setup as above, but the quota flag is off — plain round-robin (insertion order)
        // applies, matching the pre-Phase-2 behavior byte-for-byte.
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, false);
        RecommendedJob poland = job("Poland", 93);
        RecommendedJob germany = job("Germany", 92);
        List<RecommendedJob> ranked = List.of(poland, germany);

        List<RecommendedJob> result = diversifier.diversify(ranked);

        assertThat(result.get(0)).isEqualTo(poland);
        assertThat(result.get(1)).isEqualTo(germany);
    }

    @Test
    void aCountryWithNoSoftQuotaEntryIsNeverExcludedJustSortsLast() {
        RecommendationDiversifier diversifier = new RecommendationDiversifier(true, true);
        RecommendedJob india = job("India", 93); // not in SOFT_QUOTA_PCT at all
        RecommendedJob germany = job("Germany", 92);
        List<RecommendedJob> ranked = List.of(india, germany);

        List<RecommendedJob> result = diversifier.diversify(ranked);

        assertThat(result).containsExactlyInAnyOrder(india, germany); // both present, neither dropped
        assertThat(result.get(0)).isEqualTo(germany); // Germany (quota 25) pulled before India (quota 0)
    }
}
