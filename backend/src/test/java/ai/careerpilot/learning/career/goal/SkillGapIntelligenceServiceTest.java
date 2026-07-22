package ai.careerpilot.learning.career.goal;

import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.JobRecommendationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillGapIntelligenceServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final JobRecommendationRepository repo = mock(JobRecommendationRepository.class);

    private SkillGapIntelligenceService service(boolean enabled) {
        return new SkillGapIntelligenceService(repo, enabled);
    }

    @Test
    void disabledReturnsEmptyMap() {
        assertThat(service(false).compute(userId)).isEmpty();
    }

    @Test
    void noRecommendationsYieldsEmptyListsNotNulls() {
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());
        Map<String, Object> result = service(true).compute(userId);
        assertThat(result.get("strengths")).isEqualTo(List.of());
        assertThat(result.get("criticalSkills")).isEqualTo(List.of());
    }

    @Test
    void criticalSkillsAreThoseMissingInAtLeast40PercentOfJobs() {
        List<JobRecommendation> recs = List.of(
                rec("java", "kubernetes"),
                rec("java", "kubernetes"),
                rec("python", "docker"));
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(recs);

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        List<String> critical = (List<String>) result.get("criticalSkills");
        assertThat(critical).contains("kubernetes"); // missing in 2/3 = 67%
    }

    @Test
    void strengthsComeFromRealMatchingSkillsAcrossRecommendations() {
        List<JobRecommendation> recs = List.of(rec("java,spring", "docker"), rec("java", "kafka"));
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(recs);

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        List<String> strengths = (List<String>) result.get("strengths");
        assertThat(strengths).contains("java");
    }

    @Test
    void emergingSkillsAndIndustryTrendsAreNeverFabricated() {
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec("java", "docker")));
        Map<String, Object> result = service(true).compute(userId);
        assertThat((String) result.get("emergingSkillsNote")).contains("not computed");
        assertThat((String) result.get("industryTrendsNote")).contains("not computed");
    }

    @Test
    void suggestionsAreProvidedForLearningPriorityItems() {
        List<JobRecommendation> recs = List.of(rec("java", "kubernetes"), rec("java", "kubernetes"), rec("java", "kubernetes"));
        when(repo.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(recs);

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> suggestions = (Map<String, Object>) result.get("suggestions");
        assertThat(suggestions).containsKey("kubernetes");
    }

    private JobRecommendation rec(String matching, String missing) {
        return JobRecommendation.builder().id(UUID.randomUUID()).userId(userId).jobId(UUID.randomUUID())
                .matchScore(50).matchingSkills(matching).missingSkills(missing).build();
    }
}
