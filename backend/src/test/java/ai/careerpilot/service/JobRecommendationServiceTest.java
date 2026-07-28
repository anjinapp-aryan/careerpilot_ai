package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.jobdiscovery.JobMatchingService;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.international.InternationalJobRankingService;
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

    private JobRecommendationService service() {
        return new JobRecommendationService(runs, jobs, recommendations, matching, internationalRanking, scoring,
                candidateProfiles, resumes, true, 70);
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
}
