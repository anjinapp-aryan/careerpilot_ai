package ai.careerpilot.learning.career.goal;

import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.repo.CandidateProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CareerGoalPlannerServiceTest {

    private final UUID userId = UUID.randomUUID();
    private CandidateProfileRepository profiles;
    private SkillGapIntelligenceService skillGap;
    private PromotionReadinessService promotionReadiness;
    private CompanyKnowledgeService companyKnowledge;

    @BeforeEach
    void setUp() {
        profiles = mock(CandidateProfileRepository.class);
        skillGap = mock(SkillGapIntelligenceService.class);
        promotionReadiness = mock(PromotionReadinessService.class);
        companyKnowledge = mock(CompanyKnowledgeService.class);
        when(skillGap.compute(any())).thenReturn(Map.of("learningPriority", List.of(), "criticalSkills", List.of()));
        when(promotionReadiness.compute(any())).thenReturn(Map.of("readinessByLevel", Map.of()));
        when(companyKnowledge.search(any(), any())).thenReturn(List.of());
    }

    private CareerGoalPlannerService service(boolean enabled) {
        return new CareerGoalPlannerService(profiles, skillGap, promotionReadiness, companyKnowledge, enabled);
    }

    @Test
    void disabledReturnsEmptyMap() {
        assertThat(service(false).plan(userId, "Staff Engineer")).isEmpty();
    }

    @Test
    void unrecognizedGoalReturnsErrorAndSupportedGoalsList() {
        Map<String, Object> result = service(true).plan(userId, "Chief Wizard");
        assertThat(result.get("error")).isEqualTo("unrecognized goal");
        assertThat(result.get("supportedGoals")).isEqualTo(CareerLevelTaxonomy.supportedGoals());
    }

    @Test
    void planIncludesCurrentAndTargetPositionFromRealProfile() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(
                CandidateProfile.builder().id(UUID.randomUUID()).userId(userId).currentRole("Senior Engineer").build()));

        Map<String, Object> result = service(true).plan(userId, "Staff Engineer");

        assertThat(result.get("currentPosition")).isEqualTo("Senior Engineer");
        assertThat(result.get("targetPosition")).isEqualTo("Staff Engineer");
        assertThat(result.get("targetLevel")).isEqualTo(CareerLevelTaxonomy.STAFF);
    }

    @Test
    void expectedSalaryIsHonestlyUnavailable() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        Map<String, Object> result = service(true).plan(userId, "Tech Lead");
        assertThat((String) result.get("expectedSalaryNote")).contains("not available");
    }

    @Test
    void unknownCurrentLevelIsFlaggedAsARisk() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        Map<String, Object> result = service(true).plan(userId, "Tech Lead");
        @SuppressWarnings("unchecked")
        List<String> risks = (List<String>) result.get("riskFactors");
        assertThat(risks).anyMatch(r -> r.contains("could not be classified"));
    }
}
