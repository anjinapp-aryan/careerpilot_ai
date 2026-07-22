package ai.careerpilot.learning.career.goal;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.InterviewRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionReadinessServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);

    private PromotionReadinessService service(boolean enabled) {
        return new PromotionReadinessService(profiles, interviews, enabled);
    }

    @Test
    void disabledReturnsEmptyMap() {
        assertThat(service(false).compute(userId)).isEmpty();
    }

    @Test
    void ungroundedDimensionsAreAlwaysNotComputed() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) result.get("dimensions");
        for (String key : List.of("architecture", "devops", "communication", "mentoring", "businessKnowledge", "ownership", "decisionMaking")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dim = (Map<String, Object>) dims.get(key);
            assertThat(dim.get("value")).isEqualTo("NOT_COMPUTED");
        }
    }

    @Test
    void noCandidateProfileMeansCurrentLevelIsNotComputedForEveryTarget() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> byLevel = (Map<String, Object>) result.get("readinessByLevel");
        @SuppressWarnings("unchecked")
        Map<String, Object> senior = (Map<String, Object>) byLevel.get(CareerLevelTaxonomy.SENIOR);
        assertThat(senior.get("readiness")).isEqualTo("NOT_COMPUTED");
    }

    @Test
    void alreadyAtOrAboveTargetLevelIsRecognized() {
        CandidateProfile profile = CandidateProfile.builder().id(UUID.randomUUID()).userId(userId)
                .currentRole("Staff Engineer").yearsExperience(12).build();
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> byLevel = (Map<String, Object>) result.get("readinessByLevel");
        @SuppressWarnings("unchecked")
        Map<String, Object> senior = (Map<String, Object>) byLevel.get(CareerLevelTaxonomy.SENIOR);
        assertThat(senior.get("readiness")).isEqualTo("ALREADY_AT_OR_ABOVE_TARGET_LEVEL");
    }

    @Test
    void systemDesignDimensionComputesFromRealInterviewOutcomes() {
        CandidateProfile profile = CandidateProfile.builder().id(UUID.randomUUID()).userId(userId)
                .currentRole("Senior Engineer").yearsExperience(6).build();
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(UUID.randomUUID())
                        .interviewType(Interview.TYPE_SYSTEM_DESIGN).result(Interview.RESULT_PASSED).build()));

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) result.get("dimensions");
        @SuppressWarnings("unchecked")
        Map<String, Object> systemDesign = (Map<String, Object>) dims.get("systemDesign");
        assertThat(systemDesign.get("value")).isEqualTo("HIGH");
    }

    @Test
    void readinessScoreIsBoundedZeroToHundred() {
        CandidateProfile profile = CandidateProfile.builder().id(UUID.randomUUID()).userId(userId)
                .currentRole("Junior Developer").yearsExperience(1).build();
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        Map<String, Object> result = service(true).compute(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> byLevel = (Map<String, Object>) result.get("readinessByLevel");
        @SuppressWarnings("unchecked")
        Map<String, Object> principal = (Map<String, Object>) byLevel.get(CareerLevelTaxonomy.PRINCIPAL);
        Object score = principal.get("readinessScore");
        if (score != null) {
            assertThat((Integer) score).isBetween(0, 100);
        }
    }
}
