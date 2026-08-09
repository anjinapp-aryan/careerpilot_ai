package ai.careerpilot.submission;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.User;
import ai.careerpilot.execution.browser.form.AnswerResolver;
import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.ats.CandidateAtsProfileService;
import ai.careerpilot.submission.mapping.FieldMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guided Apply — the no-fabrication contract is the whole point of this class: an unresolved
 * question must be reported as {@code needsUserInput=true}, never a guess dressed up as an answer.
 */
class GuidedApplyBriefServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();

    private UserRepository users;
    private CandidateProfileRepository profiles;
    private ResumeRepository resumes;
    private GuidedApplyBriefService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        resumes = mock(ResumeRepository.class);
        ApplicationSubmissionAnswerRepository answers = mock(ApplicationSubmissionAnswerRepository.class);
        when(answers.findBySessionIdOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        CandidateAtsProfileService atsProfiles = new CandidateAtsProfileService(
                mock(ai.careerpilot.repo.CandidateAtsProfileRepository.class), false);
        FieldMappingService fieldMapping = new FieldMappingService(users, profiles, atsProfiles);
        AnswerResolver answerResolver = new AnswerResolver(fieldMapping, users, answers);
        service = new GuidedApplyBriefService(users, profiles, resumes, answerResolver);
    }

    @Test
    void withNoProfileDataEveryAnswerNeedsUserInputAndIsNeverFabricated() {
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(resumes.findByIdAndUserId(resumeId, userId)).thenReturn(Optional.empty());

        GuidedApplyBriefService.GuidedApplyBrief brief = service.buildFor(userId, resumeId);

        assertThat(brief.candidateName()).isEqualTo("Ada Lovelace");
        assertThat(brief.recommendedAnswers()).isNotEmpty();
        assertThat(brief.recommendedAnswers()).allSatisfy(a -> {
            assertThat(a.needsUserInput()).isTrue();
            assertThat(a.value()).isNull();
            assertThat(a.confidence()).isNull();
        });
        // Only Name/Email are known — nothing else is invented into the profile section.
        assertThat(brief.profile()).extracting(GuidedApplyBriefService.ProfileFact::label)
                .containsExactlyInAnyOrder("Name", "Email");
    }

    @Test
    void withSeededProfileDataAnswersResolveWithHighConfidence() {
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(
                CandidateProfile.builder().userId(userId).visaRequired(false).yearsExperience(9)
                        .skillsJson("[\"Java\",\"Spring Boot\"]").build()));
        when(resumes.findByIdAndUserId(resumeId, userId)).thenReturn(Optional.of(
                Resume.builder().id(resumeId).filename("Ada_Resume_2026.pdf").build()));

        GuidedApplyBriefService.GuidedApplyBrief brief = service.buildFor(userId, resumeId);

        Map<String, GuidedApplyBriefService.RecommendedAnswer> byField = brief.recommendedAnswers().stream()
                .collect(java.util.stream.Collectors.toMap(GuidedApplyBriefService.RecommendedAnswer::canonicalField, a -> a));

        GuidedApplyBriefService.RecommendedAnswer sponsorship = byField.get(CanonicalField.VISA_SPONSORSHIP.name());
        assertThat(sponsorship.needsUserInput()).isFalse();
        assertThat(sponsorship.value()).isEqualTo("false");
        assertThat(sponsorship.source()).isEqualTo("CandidateProfile.visaRequired");
        assertThat(sponsorship.confidence()).isEqualTo("HIGH");

        GuidedApplyBriefService.RecommendedAnswer years = byField.get(CanonicalField.YEARS_EXPERIENCE.name());
        assertThat(years.needsUserInput()).isFalse();
        assertThat(years.value()).isEqualTo("9");

        assertThat(brief.resumeFilename()).isEqualTo("Ada_Resume_2026.pdf");
        assertThat(brief.profile()).extracting(GuidedApplyBriefService.ProfileFact::label)
                .contains("Name", "Email", "Primary Skills", "Resume", "Sponsorship Required");
    }

    /**
     * Guided Apply Hardening — a resumeId that does not belong to userId must never leak that
     * resume's filename. The query is ownership-scoped ({@code findByIdAndUserId}), so a
     * mismatched (resumeId, userId) pair — however it arose — resolves like "no resume" rather
     * than fetching another user's file.
     */
    @Test
    void resumeIdBelongingToAnotherUserIsNeverLeaked() {
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        // Deliberately NOT stubbed for (resumeId, userId) — only a mismatched lookup would return
        // this, and the mock's default (unstubbed) answer for that combination is Optional.empty().

        GuidedApplyBriefService.GuidedApplyBrief brief = service.buildFor(userId, resumeId);

        assertThat(brief.resumeFilename()).isNull();
    }

    @Test
    void missingResumeIdLeavesResumeFilenameNullNeverFabricated() {
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());

        GuidedApplyBriefService.GuidedApplyBrief brief = service.buildFor(userId, null);

        assertThat(brief.resumeFilename()).isNull();
        assertThat(brief.profile()).extracting(GuidedApplyBriefService.ProfileFact::label)
                .doesNotContain("Resume");
    }
}
