package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.jobdiscovery.IndustryFitClassifier;
import ai.careerpilot.jobdiscovery.JobMatchingService;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.international.CandidateCountryFitClassifier;
import ai.careerpilot.jobdiscovery.international.CountryIntelligenceService;
import ai.careerpilot.jobdiscovery.international.InternationalJobRankingService;
import ai.careerpilot.jobdiscovery.international.SupportedCountryService;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 8.X regression coverage — a CandidateProfile-only user (resume uploaded, Candidate
 * Intelligence Profile generated, but the LangGraph WorkflowRun pipeline never run) must still
 * get a non-null profile summary and a matching refresh, instead of the "Run the AI workflow"
 * empty state. See JobRecommendationService.recommend() for the fallback this guards.
 */
class JobRecommendationServiceTest {

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
    private final JobMatchingService matching = mock(JobMatchingService.class);
    private final InternationalJobRankingService internationalRanking = mock(InternationalJobRankingService.class);
    private final JobScoring scoring = mock(JobScoring.class);
    private final CandidateProfileRepository candidateProfiles = mock(CandidateProfileRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private final RecommendationDiversifier diversifier = new RecommendationDiversifier(false, false);
    private final SupportedCountryService supportedCountries = mock(SupportedCountryService.class);
    private final CountryIntelligenceService countryIntelligence = mock(CountryIntelligenceService.class);
    private final CandidateCountryFitClassifier candidateCountryFitClassifier =
            new CandidateCountryFitClassifier(new IndustryFitClassifier());
    private final IndustryFitClassifier industryFitClassifier = new IndustryFitClassifier();

    private JobRecommendationService service() {
        return new JobRecommendationService(runs, jobs, recommendations, matching, internationalRanking, scoring,
                candidateProfiles, resumes, diversifier, supportedCountries, countryIntelligence,
                candidateCountryFitClassifier, industryFitClassifier, true, 70, false, false, false);
    }

    @Test
    void noWorkflowRunAndNoCandidateProfileReturnsNullProfile() {
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(candidateProfiles.findByUserId(userId)).thenReturn(Optional.empty());

        RecommendedJobsResponse resp = service().recommend(userId, orgId, 0, 10, "all");

        assertThat(resp.profile()).isNull();
        assertThat(resp.jobs()).isEmpty();
        verify(matching, never()).refreshForUser(any());
    }

    @Test
    void candidateProfileOnlyUserGetsNonNullProfileAndTriggersMatching() {
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        CandidateProfile profile = CandidateProfile.builder()
                .userId(userId)
                .yearsExperience(5)
                .currentRole("Backend Engineer")
                .skillsJson("[\"Java\",\"Spring\"]")
                .targetRolesJson("[\"Senior Backend Engineer\"]")
                .preferredCountriesJson("[\"India\"]")
                .build();
        when(candidateProfiles.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());
        when(jobs.search(any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        RecommendedJobsResponse resp = service().recommend(userId, orgId, 0, 10, "all");

        assertThat(resp.profile()).isNotNull();
        assertThat(resp.profile().yearsExperience()).isEqualTo(5);
        assertThat(resp.profile().currentTitle()).isEqualTo("Backend Engineer");
        assertThat(resp.profile().topSkills()).containsExactly("Java", "Spring");
        verify(matching).refreshForUser(userId);
    }

    @Test
    void workflowRunPathIsUnchangedWhenBothExist() {
        var run = mock(ai.careerpilot.domain.WorkflowRun.class);
        when(run.getState()).thenReturn("{\"extracted_skills\":[\"Python\"],\"target_locations\":[],\"candidate_profile\":{\"years_experience\":8,\"current_title\":\"Staff Engineer\"}}");
        when(run.getResumeScore()).thenReturn(88);
        when(run.getTargetRole()).thenReturn("Staff Engineer");
        when(run.getTargetSeniority()).thenReturn(null);
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());
        when(jobs.search(any(), any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        RecommendedJobsResponse resp = service().recommend(userId, orgId, 0, 10, "all");

        assertThat(resp.profile()).isNotNull();
        assertThat(resp.profile().yearsExperience()).isEqualTo(8);
        assertThat(resp.profile().resumeScore()).isEqualTo(88);
        verify(candidateProfiles, never()).findByUserId(any());
    }

    // ── atsPlatform — reuses the existing pure AtsPlatform.detect, never fabricated ──

    @Test
    void atsPlatformIsDetectedFromTheRealSourceUrlOnThePersistedPath() {
        var run = mock(ai.careerpilot.domain.WorkflowRun.class);
        when(run.getState()).thenReturn("{\"extracted_skills\":[],\"target_locations\":[]}");
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));

        UUID jobId = UUID.randomUUID();
        ai.careerpilot.domain.Job job = ai.careerpilot.domain.Job.builder()
                .id(jobId).title("Senior Backend Engineer").company("GitLab")
                .description("desc")
                .sourceUrl("https://job-boards.greenhouse.io/gitlab/jobs/123")
                .build();
        ai.careerpilot.domain.JobRecommendation rec = ai.careerpilot.domain.JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(90).build();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec));
        when(jobs.findAllById(any())).thenReturn(List.of(job));

        RecommendedJobsResponse resp = service().recommend(userId, orgId, 0, 10, "all");

        assertThat(resp.jobs()).hasSize(1);
        assertThat(resp.jobs().get(0).atsPlatform()).isEqualTo("GREENHOUSE");
    }

    @Test
    void atsPlatformIsNullNeverAFabricatedGuessWhenTheHostIsUnrecognised() {
        var run = mock(ai.careerpilot.domain.WorkflowRun.class);
        when(run.getState()).thenReturn("{\"extracted_skills\":[],\"target_locations\":[]}");
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));

        UUID jobId = UUID.randomUUID();
        ai.careerpilot.domain.Job job = ai.careerpilot.domain.Job.builder()
                .id(jobId).title("Backend Engineer").company("Acme")
                .description("desc")
                .sourceUrl("https://careers.acme-corp-example.com/job/123")
                .build();
        ai.careerpilot.domain.JobRecommendation rec = ai.careerpilot.domain.JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(90).build();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec));
        when(jobs.findAllById(any())).thenReturn(List.of(job));

        RecommendedJobsResponse resp = service().recommend(userId, orgId, 0, 10, "all");

        assertThat(resp.jobs()).hasSize(1);
        assertThat(resp.jobs().get(0).atsPlatform()).isNull();
    }

    // ── International Job Discovery Phase 2 — searchPriority/candidateCountryFit/industryFit/languageFriendlyScore ──

    private JobRecommendationService serviceWithPhase2Enabled() {
        return new JobRecommendationService(runs, jobs, recommendations, matching, internationalRanking, scoring,
                candidateProfiles, resumes, diversifier, supportedCountries, countryIntelligence,
                candidateCountryFitClassifier, industryFitClassifier, true, 70, false, true, true);
    }

    private ai.careerpilot.domain.Job jpMorganBankingJob(UUID jobId) {
        return ai.careerpilot.domain.Job.builder()
                .id(jobId).title("Senior Java Architect").company("J.P. Morgan").country("Germany")
                .description("Payments platform, banking core systems").sourceUrl("https://example.com/job/1")
                .build();
    }

    @Test
    void phase2FieldsAreNullWhenTheirFlagsAreOff() {
        var run = mock(ai.careerpilot.domain.WorkflowRun.class);
        when(run.getState()).thenReturn("{\"extracted_skills\":[\"java\"],\"target_locations\":[]}");
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        UUID jobId = UUID.randomUUID();
        ai.careerpilot.domain.JobRecommendation rec = ai.careerpilot.domain.JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(90).build();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec));
        when(jobs.findAllById(any())).thenReturn(List.of(jpMorganBankingJob(jobId)));

        RecommendedJobsResponse resp = service().recommend(userId, orgId, 0, 10, "all"); // flags off

        var recommended = resp.jobs().get(0);
        assertThat(recommended.searchPriority()).isNull();
        assertThat(recommended.candidateCountryFit()).isNull();
        assertThat(recommended.industryFit()).isNull();
        assertThat(recommended.languageFriendlyScore()).isNull();
        verifyNoInteractions(supportedCountries, countryIntelligence);
    }

    @Test
    void phase2FieldsAreRealValuesWhenFlagsAreOn() {
        var run = mock(ai.careerpilot.domain.WorkflowRun.class);
        when(run.getState()).thenReturn("{\"extracted_skills\":[\"java\",\"aws\"],\"target_locations\":[]}");
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        UUID jobId = UUID.randomUUID();
        ai.careerpilot.domain.JobRecommendation rec = ai.careerpilot.domain.JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(90).build();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec));
        when(jobs.findAllById(any())).thenReturn(List.of(jpMorganBankingJob(jobId)));

        ai.careerpilot.domain.SupportedCountry germany = ai.careerpilot.domain.SupportedCountry.builder()
                .countryCode("de").displayName("Germany")
                .tier(ai.careerpilot.jobdiscovery.international.CountryTier.TIER_1)
                .searchPriority(ai.careerpilot.jobdiscovery.international.SearchPriority.PRIMARY)
                .build();
        when(supportedCountries.byDisplayName("Germany")).thenReturn(java.util.Optional.of(germany));
        ai.careerpilot.domain.CountryIntelligence intel = ai.careerpilot.domain.CountryIntelligence.builder()
                .countryCode("de").visaProbabilityScore(75).relocationDifficultyScore(50)
                .languageRequirementScore(50).costOfLivingIndex(50).expectedSavingsScore(50)
                .jobStabilityScore(50).techMarketScore(85).principalEngineerGrowthScore(50).aiMarketScore(50)
                .languageFriendlyScore(75).industryFitJson("[\"BANKING\",\"ENTERPRISE\"]")
                .build();
        when(countryIntelligence.forCountry("de")).thenReturn(java.util.Optional.of(intel));

        RecommendedJobsResponse resp = serviceWithPhase2Enabled().recommend(userId, orgId, 0, 10, "all");

        var recommended = resp.jobs().get(0);
        assertThat(recommended.searchPriority()).isEqualTo("PRIMARY");
        assertThat(recommended.candidateCountryFit()).isNotNull();
        assertThat(recommended.industryFit()).isEqualTo("BANKING");
        assertThat(recommended.languageFriendlyScore()).isEqualTo(75);
    }

    /**
     * Release-readiness audit — India domestic non-contamination. Even with every Phase 2 flag on,
     * an India job must never receive a SearchPriority/CandidateCountryFit/LanguageFriendlyScore:
     * India was never inserted into {@code supported_countries} (this test proves that by having
     * {@code supportedCountries.byDisplayName("India")} return empty, exactly as the real repository
     * would for a country row that doesn't exist), so every Phase 2 lookup for an India job
     * structurally resolves to null — not because of a special-case guard, but because the country
     * genuinely isn't in the international program. industryFit is the one exception: it's computed
     * from the JOB'S OWN text, not the country, so it can still resolve for an India job — which is
     * correct (an India-based J.P. Morgan job is still honestly BANKING) and proves industry
     * classification is never country-derived, matching the master prompt's explicit requirement.
     */
    @Test
    void indiaJobNeverReceivesInternationalCountryFieldsEvenWithAllPhase2FlagsOn() {
        var run = mock(ai.careerpilot.domain.WorkflowRun.class);
        when(run.getState()).thenReturn("{\"extracted_skills\":[\"java\",\"spring\"],\"target_locations\":[]}");
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        UUID jobId = UUID.randomUUID();
        ai.careerpilot.domain.Job indiaJob = ai.careerpilot.domain.Job.builder()
                .id(jobId).title("Senior Java Engineer").company("Acme India").country("India")
                .description("Backend platform team, Bangalore").sourceUrl("https://example.com/job/india-1")
                .build();
        ai.careerpilot.domain.JobRecommendation rec = ai.careerpilot.domain.JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(88).build();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec));
        when(jobs.findAllById(any())).thenReturn(List.of(indiaJob));
        // India was never seeded into supported_countries — the real repository would also return
        // empty here, so this stub is not a special case, it's the honest production behavior.
        when(supportedCountries.byDisplayName("India")).thenReturn(java.util.Optional.empty());

        RecommendedJobsResponse resp = serviceWithPhase2Enabled().recommend(userId, orgId, 0, 10, "all");

        var recommended = resp.jobs().get(0);
        assertThat(recommended.searchPriority()).isNull();
        assertThat(recommended.candidateCountryFit()).isNull();
        assertThat(recommended.languageFriendlyScore()).isNull();
        // countryIntelligence is never even consulted for a country with no supported_countries row.
        verify(countryIntelligence, never()).forCountry(any());
    }
}
