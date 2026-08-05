package ai.careerpilot.employerquestion;

import ai.careerpilot.domain.EmployerAnswer;
import ai.careerpilot.domain.EmployerQuestion;
import ai.careerpilot.repo.EmployerAnswerRepository;
import ai.careerpilot.repo.EmployerQuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

/** Phase D — the human review workflow and the reuse guarantee. */
class EmployerAnswerServiceTest {

    private final EmployerQuestionRepository questionRepo = mock(EmployerQuestionRepository.class);
    private final EmployerAnswerRepository answerRepo = mock(EmployerAnswerRepository.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();

    private static final String QUESTION = "What is your current country of residence?";

    private EmployerQuestionService questions;
    private EmployerAnswerService answers;
    private EmployerQuestion question;

    @BeforeEach
    void setUp() {
        questions = new EmployerQuestionService(questionRepo, true);
        answers = new EmployerAnswerService(answerRepo, questions, true);

        Instant now = Instant.now();
        question = EmployerQuestion.builder()
                .id(UUID.randomUUID()).originalText(QUESTION)
                .normalizedText(QuestionNormalizer.normalize(QUESTION))
                .canonicalField("COUNTRY").questionCategory("OTHER").questionType("TEXT")
                .timesSeen(1).firstSeenAt(now).lastSeenAt(now).build();

        when(questionRepo.findTop200ByOrderByLastSeenAtDesc()).thenReturn(List.of(question));
        when(answerRepo.save(any(EmployerAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepo.save(any(EmployerQuestion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private EmployerAnswer draftFor(AnswerConfidence confidence) {
        when(answerRepo.findByUserIdAndQuestionId(userId, question.getId())).thenReturn(Optional.empty());
        return answers.draft(userId, question.getId(), "India", confidence, "profile").orElseThrow();
    }

    @Test
    @DisplayName("with the flag off nothing resolves and no repository is touched")
    void darkByDefault() {
        EmployerAnswerService off = new EmployerAnswerService(answerRepo,
                new EmployerQuestionService(questionRepo, false), false);

        AnswerResolution result = off.resolve(userId, QUESTION, "COUNTRY");

        assertThat(result.usable()).isFalse();
        assertThat(result.reason()).contains("disabled");
        verifyNoInteractions(answerRepo);
    }

    @Test
    @DisplayName("an unseen question needs human review — it is never answered")
    void unseenQuestionNeedsReview() {
        when(questionRepo.findTop200ByOrderByLastSeenAtDesc()).thenReturn(List.of());

        AnswerResolution result = answers.resolve(userId, "Do you hold a forklift licence?", "SCREENING_QUESTION");

        assertThat(result.usable()).isFalse();
        assertThat(result.answerText()).isNull();
        assertThat(result.reason()).contains("No verified answer available");
    }

    @Test
    @DisplayName("resolve never creates a draft — an answer nobody reviews must not exist")
    void resolveNeverWrites() {
        when(answerRepo.findByUserIdAndQuestionId(userId, question.getId())).thenReturn(Optional.empty());

        AnswerResolution result = answers.resolve(userId, QUESTION, "COUNTRY");

        assertThat(result.usable()).isFalse();
        assertThat(result.reason()).contains("no answer for it yet");
        verify(answerRepo, never()).save(any());
    }

    @Test
    @DisplayName("a draft is never usable, however confident it claims to be")
    void draftIsNeverUsable() {
        EmployerAnswer draft = draftFor(AnswerConfidence.VERIFIED);
        assertThat(draft.isApproved()).isFalse();

        when(answerRepo.findByUserIdAndQuestionId(userId, question.getId())).thenReturn(Optional.of(draft));
        AnswerResolution result = answers.resolve(userId, QUESTION, "COUNTRY");

        assertThat(result.usable()).isFalse();
        assertThat(result.answerText()).isNull();
        assertThat(result.reason()).contains("no human has approved it");
    }

    @Test
    @DisplayName("an AI draft stays unusable and its band is not silently promoted")
    void aiDraftStaysUnusable() {
        EmployerAnswer draft = draftFor(AnswerConfidence.AI_SUGGESTED);

        assertThat(draft.getConfidence()).isEqualTo(AnswerConfidence.AI_SUGGESTED.name());
        assertThat(draft.isApproved()).isFalse();
    }

    @Test
    @DisplayName("approval makes an answer usable and records who and when")
    void approvalGrantsUsability() {
        EmployerAnswer draft = draftFor(AnswerConfidence.AI_SUGGESTED);
        draft.setId(UUID.randomUUID());
        when(answerRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        EmployerAnswer approved = answers.approve(userId, draft.getId(), userId).orElseThrow();

        assertThat(approved.isApproved()).isTrue();
        assertThat(approved.getApprovedBy()).isEqualTo(userId);
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(approved.getConfidence()).isEqualTo(AnswerConfidence.HUMAN_APPROVED.name());

        when(answerRepo.findByUserIdAndQuestionId(userId, question.getId())).thenReturn(Optional.of(approved));
        AnswerResolution result = answers.resolve(userId, QUESTION, "COUNTRY");

        assertThat(result.usable()).isTrue();
        assertThat(result.answerText()).isEqualTo("India");
        assertThat(result.reason()).contains("Reused a human-approved answer");
    }

    @Test
    @DisplayName("an approved answer is reused for a rephrasing, with no second review")
    void approvedAnswerIsReusedAcrossPhrasings() {
        EmployerAnswer draft = draftFor(AnswerConfidence.PROFILE_DERIVED);
        draft.setId(UUID.randomUUID());
        when(answerRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        EmployerAnswer approved = answers.approve(userId, draft.getId(), userId).orElseThrow();
        when(answerRepo.findByUserIdAndQuestionId(userId, question.getId())).thenReturn(Optional.of(approved));

        // A different employer, phrased differently.
        AnswerResolution result = answers.resolve(userId,
                "Please tell us your current residence country", "COUNTRY");

        assertThat(result.usable()).isTrue();
        assertThat(result.answerText()).isEqualTo("India");
        assertThat(result.questionId()).isEqualTo(question.getId());
        // Usage bookkeeping, not answer creation.
        assertThat(approved.getUsageCount()).isEqualTo(1);
        assertThat(approved.getLastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("re-drafting an approved answer revokes approval")
    void redraftRevokesApproval() {
        EmployerAnswer draft = draftFor(AnswerConfidence.AI_SUGGESTED);
        draft.setId(UUID.randomUUID());
        when(answerRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        EmployerAnswer approved = answers.approve(userId, draft.getId(), userId).orElseThrow();
        assertThat(approved.isApproved()).isTrue();

        when(answerRepo.findByUserIdAndQuestionId(userId, question.getId())).thenReturn(Optional.of(approved));
        EmployerAnswer redrafted = answers.draft(userId, question.getId(), "Germany",
                AnswerConfidence.AI_SUGGESTED, "profile").orElseThrow();

        // The human vouched for "India", not "Germany".
        assertThat(redrafted.isApproved()).isFalse();
        assertThat(redrafted.getApprovedAt()).isNull();
        assertThat(redrafted.getApprovedBy()).isNull();
    }

    @Test
    @DisplayName("another user's answer cannot be approved")
    void multiTenantIsolation() {
        EmployerAnswer draft = draftFor(AnswerConfidence.AI_SUGGESTED);
        draft.setId(UUID.randomUUID());
        when(answerRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThat(answers.approve(otherUser, draft.getId(), otherUser)).isEmpty();
        assertThat(draft.isApproved()).isFalse();
    }

    @Test
    @DisplayName("an approved answer carrying an untrusted band is refused, not trusted")
    void approvedButUntrustedBandIsRefused() {
        // A data inconsistency (hand-edited row, failed migration). Approval alone must not override
        // the confidence gate.
        EmployerAnswer inconsistent = EmployerAnswer.builder()
                .id(UUID.randomUUID()).userId(userId).questionId(question.getId())
                .answerText("India").confidence(AnswerConfidence.AI_SUGGESTED.name())
                .approved(true).approvedAt(Instant.now()).usageCount(0)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(answerRepo.findByUserIdAndQuestionId(userId, question.getId()))
                .thenReturn(Optional.of(inconsistent));

        AnswerResolution result = answers.resolve(userId, QUESTION, "COUNTRY");

        assertThat(result.usable()).isFalse();
        assertThat(result.answerText()).isNull();
        assertThat(result.reason()).contains("not usable by automation");
    }

    @Test
    @DisplayName("recording the same question twice produces one row, seen twice")
    void duplicateQuestionsDeduplicate() {
        when(questionRepo.findByNormalizedText(any())).thenReturn(Optional.of(question));

        questions.record(new EmployerQuestionService.Observation(
                "What country do you currently live in?", "COUNTRY", "OTHER", "TEXT", true,
                "Employer B", "LEVER"));

        assertThat(question.getTimesSeen()).isEqualTo(2);
        // Provenance updates, identity does not.
        assertThat(question.getAtsPlatform()).isEqualTo("LEVER");
        assertThat(question.getNormalizedText()).isEqualTo(QuestionNormalizer.normalize(QUESTION));
    }

    @Test
    @DisplayName("a meaningless question is never stored")
    void meaninglessQuestionIsNotStored() {
        assertThat(questions.record(new EmployerQuestionService.Observation(
                "the a of to", "UNKNOWN", "OTHER", "TEXT", false, null, null))).isEmpty();
        verify(questionRepo, never()).save(any());
    }
}
