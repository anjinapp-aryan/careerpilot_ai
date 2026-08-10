package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * International Job Discovery Engine, Phase 1 — {@link InternationalJobScoring}. Pins the exact
 * 40/15/10/10/10/5/5/5 weighted-sum formula from the program spec against known component scores,
 * and confirms it's independent of {@link JobScoring#scoreV2}'s own weights.
 */
class InternationalJobScoringTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final JobScoring jobScoring = new JobScoring(taxonomy);
    private final InternationalJobScoring scoring = new InternationalJobScoring(jobScoring, taxonomy);

    private static CountryIntelligence intel(int visa, int principalGrowth, int aiMarket) {
        return CountryIntelligence.builder()
                .countryCode("de").visaProbabilityScore(visa).relocationDifficultyScore(50)
                .languageRequirementScore(50).costOfLivingIndex(50).expectedSavingsScore(50)
                .jobStabilityScore(50).techMarketScore(50)
                .principalEngineerGrowthScore(principalGrowth).aiMarketScore(aiMarket)
                .build();
    }

    @Test
    void rankScoreIsTheExactWeightedSumFromTheSpec() {
        Job job = Job.builder()
                .title("Principal Software Engineer").description("Java Spring Boot Kubernetes AWS")
                .skills("java,spring boot,kubernetes,aws")
                .companySize("ENTERPRISE").remoteType("REMOTE").sponsorshipAvailable(true)
                .build();
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                java.util.List.of("java", "spring boot", "kubernetes", "aws"), "Principal Software Engineer",
                java.util.List.of(), 12, null);

        InternationalJobScoring.InternationalScoreResult r =
                scoring.score(job, job.getSkills(), ctx, JobScoring.PreferenceContext.empty(), intel(75, 75, 78));

        // Manually recompute the expected weighted sum from the returned component scores to pin
        // the formula itself (40/15/10/10/10/5/5/5), independent of any one component's exact value.
        int expected = Math.round(r.skillScore() * 0.40f + r.visaProbabilityScore() * 0.15f
                + r.salaryScore() * 0.10f + r.careerGrowthScore() * 0.10f + r.companyStabilityScore() * 0.10f
                + r.remoteFlexibilityScore() * 0.05f + r.principalEngineerGrowthScore() * 0.05f
                + r.aiTechStackScore() * 0.05f);
        expected = Math.min(100, Math.max(0, expected));

        assertThat(r.rankScore()).isEqualTo(expected);
    }

    @Test
    void companyStabilityScoreReflectsCompanySize() {
        Job enterprise = Job.builder().title("Backend Engineer").description("").companySize("ENTERPRISE").build();
        Job startup = Job.builder().title("Backend Engineer").description("").companySize("STARTUP").build();
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                java.util.List.of(), "Backend Engineer", java.util.List.of(), null, null);

        var rEnterprise = scoring.score(enterprise, null, ctx, JobScoring.PreferenceContext.empty(), null);
        var rStartup = scoring.score(startup, null, ctx, JobScoring.PreferenceContext.empty(), null);

        assertThat(rEnterprise.companyStabilityScore()).isEqualTo(90);
        assertThat(rStartup.companyStabilityScore()).isEqualTo(40);
    }

    @Test
    void remoteFlexibilityScoreReflectsRemoteType() {
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                java.util.List.of(), "Backend Engineer", java.util.List.of(), null, null);
        Job remote = Job.builder().title("Backend Engineer").description("").remoteType("REMOTE").build();
        Job onsite = Job.builder().title("Backend Engineer").description("").remoteType("ONSITE").build();

        assertThat(scoring.score(remote, null, ctx, JobScoring.PreferenceContext.empty(), null).remoteFlexibilityScore())
                .isEqualTo(100);
        assertThat(scoring.score(onsite, null, ctx, JobScoring.PreferenceContext.empty(), null).remoteFlexibilityScore())
                .isEqualTo(30);
    }

    @Test
    void visaProbabilityBlendsCountryIntelWithJobSponsorshipSignal() {
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                java.util.List.of(), "Backend Engineer", java.util.List.of(), null, null);
        Job sponsored = Job.builder().title("Backend Engineer").description("").sponsorshipAvailable(true).build();
        Job notSponsored = Job.builder().title("Backend Engineer").description("").sponsorshipAvailable(false).build();

        int sponsoredScore = scoring.score(sponsored, null, ctx, JobScoring.PreferenceContext.empty(), intel(75, 75, 75)).visaProbabilityScore();
        int notSponsoredScore = scoring.score(notSponsored, null, ctx, JobScoring.PreferenceContext.empty(), intel(75, 75, 75)).visaProbabilityScore();

        assertThat(sponsoredScore).isGreaterThan(notSponsoredScore);
    }

    @Test
    void sponsorshipStatusConfirmedScoresHigherThanMentionedHigherThanUnknownHigherThanNotSupported() {
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                java.util.List.of(), "Backend Engineer", java.util.List.of(), null, null);
        Job confirmed = Job.builder().title("Backend Engineer").description("").sponsorshipStatus("CONFIRMED").build();
        Job mentioned = Job.builder().title("Backend Engineer").description("").sponsorshipStatus("MENTIONED").build();
        Job unknown = Job.builder().title("Backend Engineer").description("").sponsorshipStatus("UNKNOWN").build();
        Job notSupported = Job.builder().title("Backend Engineer").description("").sponsorshipStatus("NOT_SUPPORTED").build();

        CountryIntelligence intel = intel(75, 75, 75);
        int confirmedScore = scoring.score(confirmed, null, ctx, JobScoring.PreferenceContext.empty(), intel).visaProbabilityScore();
        int mentionedScore = scoring.score(mentioned, null, ctx, JobScoring.PreferenceContext.empty(), intel).visaProbabilityScore();
        int unknownScore = scoring.score(unknown, null, ctx, JobScoring.PreferenceContext.empty(), intel).visaProbabilityScore();
        int notSupportedScore = scoring.score(notSupported, null, ctx, JobScoring.PreferenceContext.empty(), intel).visaProbabilityScore();

        assertThat(confirmedScore).isGreaterThan(mentionedScore);
        assertThat(mentionedScore).isGreaterThan(unknownScore);
        assertThat(unknownScore).isGreaterThan(notSupportedScore);
    }

    @Test
    void sponsorshipStatusTakesPrecedenceOverTheLegacyBooleanWhenBothArePresent() {
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                java.util.List.of(), "Backend Engineer", java.util.List.of(), null, null);
        // Legacy boolean says TRUE (sponsored) but the richer signal says NOT_SUPPORTED — the
        // richer signal must win, since it's the more specific, more recently computed evidence.
        // NOT_SUPPORTED applies a stronger penalty (0.4x) than the legacy FALSE path (0.5x), so a
        // score matching the NOT_SUPPORTED formula (rather than the legacy-FALSE formula) proves
        // sponsorshipStatus, not sponsorshipAvailable, actually drove the result.
        Job job = Job.builder().title("Backend Engineer").description("")
                .sponsorshipAvailable(true).sponsorshipStatus("NOT_SUPPORTED").build();

        int score = scoring.score(job, null, ctx, JobScoring.PreferenceContext.empty(), intel(75, 75, 75)).visaProbabilityScore();

        assertThat(score).isEqualTo(Math.round(75 * 0.4f));
    }

    @Test
    void noCountryIntelFallsBackToNeutralDefaultsRatherThanThrowing() {
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                java.util.List.of(), "Backend Engineer", java.util.List.of(), null, null);
        Job job = Job.builder().title("Backend Engineer").description("").build();

        var r = scoring.score(job, null, ctx, JobScoring.PreferenceContext.empty(), null);

        assertThat(r.rankScore()).isBetween(0, 100);
    }
}
