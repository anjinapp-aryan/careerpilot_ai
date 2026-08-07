package ai.careerpilot.employerquestion;

import ai.careerpilot.domain.EmployerAnswer;
import ai.careerpilot.domain.EmployerQuestion;
import ai.careerpilot.repo.EmployerAnswerRepository;
import ai.careerpilot.repo.EmployerQuestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Work Item 1 — the employer answer library resolves a whole form in a fixed number of queries.
 *
 * <p><b>The measured defect.</b> {@code resolve()} was called once per discovered field and issued
 * two queries each time: {@code findTop200ByOrderByLastSeenAtDesc} (the entire library, pulled down
 * again for every field) and {@code findByUserIdAndQuestionId}. Across 106 live validations the
 * planning stage averaged 11,206 ms — 67% of a 16.8 s page validation — against 11 ms for discovery.
 */
class EmployerAnswerBulkResolveTest {

    private final UUID userId = UUID.randomUUID();
    private EmployerQuestionRepository questionRepo;
    private EmployerAnswerRepository answerRepo;
    private EmployerAnswerService service;
    private List<EmployerQuestion> library;

    @BeforeEach
    void setUp() {
        questionRepo = mock(EmployerQuestionRepository.class);
        answerRepo = mock(EmployerAnswerRepository.class);

        library = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            library.add(question("Question number " + i + " about your experience"));
        }
        when(questionRepo.findTop200ByOrderByLastSeenAtDesc()).thenReturn(library);
        when(answerRepo.findByUserId(userId)).thenReturn(List.of());
        when(answerRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service = new EmployerAnswerService(answerRepo,
                new EmployerQuestionService(questionRepo, true), true);
    }

    private EmployerQuestion question(String text) {
        return EmployerQuestion.builder()
                .id(UUID.randomUUID())
                .originalText(text)
                .normalizedText(QuestionNormalizer.normalize(text))
                .canonicalField("SCREENING_QUESTION")
                .questionCategory("OTHER").questionType("TEXT")
                .required(false).confidence(0).timesSeen(1)
                .firstSeenAt(Instant.now()).lastSeenAt(Instant.now())
                .build();
    }

    private EmployerAnswer approvedAnswer(UUID questionId, String text) {
        return EmployerAnswer.builder()
                .id(UUID.randomUUID()).userId(userId).questionId(questionId)
                .answerText(text)
                .confidence(AnswerConfidence.HUMAN_APPROVED.name())
                .approved(true).approvedAt(Instant.now()).approvedBy(UUID.randomUUID())
                .source("test").usageCount(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    private List<EmployerAnswerService.Lookup> lookupsForWholeForm() {
        List<EmployerAnswerService.Lookup> lookups = new ArrayList<>();
        for (EmployerQuestion q : library) {
            lookups.add(new EmployerAnswerService.Lookup(q.getOriginalText(), "SCREENING_QUESTION"));
        }
        return lookups;
    }

    // ── the acceptance criterion ──

    @Test
    @DisplayName("a 20-field form costs exactly two queries, not forty")
    void twentyFieldsCostTwoQueries() {
        Map<String, AnswerResolution> results = service.resolveAll(userId, lookupsForWholeForm());

        assertThat(results).hasSize(20);
        verify(questionRepo, times(1)).findTop200ByOrderByLastSeenAtDesc();
        verify(answerRepo, times(1)).findByUserId(userId);
        // The per-field finder must not be reachable from the bulk path at all.
        verify(answerRepo, never()).findByUserIdAndQuestionId(any(), any());
    }

    @Test
    @DisplayName("query count does not grow with field count")
    void queryCountIsIndependentOfFormSize() {
        service.resolveAll(userId, lookupsForWholeForm().subList(0, 3));
        service.resolveAll(userId, lookupsForWholeForm());

        // Two calls, two library reads, two answer reads — one pair per form, never per field.
        verify(questionRepo, times(2)).findTop200ByOrderByLastSeenAtDesc();
        verify(answerRepo, times(2)).findByUserId(userId);
    }

    @Test
    void repeatedQuestionsOnOneFormAreResolvedOnce() {
        EmployerAnswerService.Lookup same =
                new EmployerAnswerService.Lookup(library.get(0).getOriginalText(), "SCREENING_QUESTION");

        Map<String, AnswerResolution> results = service.resolveAll(userId, List.of(same, same, same));

        assertThat(results).hasSize(1);
    }

    @Test
    void usageBookkeepingIsOneBatchedWriteForTheWholeForm() {
        List<EmployerAnswer> stored = new ArrayList<>();
        for (EmployerQuestion q : library) {
            stored.add(approvedAnswer(q.getId(), "answer for " + q.getId()));
        }
        when(answerRepo.findByUserId(userId)).thenReturn(stored);

        service.resolveAll(userId, lookupsForWholeForm());

        // One saveAll, not twenty save() calls.
        verify(answerRepo, times(1)).saveAll(anyList());
        verify(answerRepo, never()).save(any());
    }

    // ── decisions must be identical to the per-field path ──

    @Test
    void anApprovedAnswerIsStillReusedAndItsUsageStillIncrements() {
        EmployerQuestion q = library.get(0);
        EmployerAnswer answer = approvedAnswer(q.getId(), "Five years of Java.");
        when(answerRepo.findByUserId(userId)).thenReturn(List.of(answer));

        Map<String, AnswerResolution> results = service.resolveAll(userId,
                List.of(new EmployerAnswerService.Lookup(q.getOriginalText(), "SCREENING_QUESTION")));

        AnswerResolution r = results.values().iterator().next();
        assertThat(r.usable()).isTrue();
        assertThat(r.answerText()).isEqualTo("Five years of Java.");
        assertThat(answer.getUsageCount()).isEqualTo(1);
    }

    @Test
    void anUnapprovedDraftIsStillRefused() {
        EmployerQuestion q = library.get(0);
        EmployerAnswer draft = approvedAnswer(q.getId(), "Draft text");
        draft.setApproved(false);
        draft.setApprovedAt(null);
        draft.setConfidence(AnswerConfidence.AI_SUGGESTED.name());
        when(answerRepo.findByUserId(userId)).thenReturn(List.of(draft));

        AnswerResolution r = service.resolveAll(userId,
                List.of(new EmployerAnswerService.Lookup(q.getOriginalText(), "SCREENING_QUESTION")))
                .values().iterator().next();

        assertThat(r.usable()).isFalse();
        assertThat(r.reason()).contains("no human has approved it");
        verify(answerRepo, never()).saveAll(anyList());
    }

    @Test
    void anApprovedAnswerWithAnUnusableConfidenceBandIsStillRefused() {
        EmployerQuestion q = library.get(0);
        EmployerAnswer answer = approvedAnswer(q.getId(), "text");
        answer.setConfidence(AnswerConfidence.AI_SUGGESTED.name());
        when(answerRepo.findByUserId(userId)).thenReturn(List.of(answer));

        AnswerResolution r = service.resolveAll(userId,
                List.of(new EmployerAnswerService.Lookup(q.getOriginalText(), "SCREENING_QUESTION")))
                .values().iterator().next();

        assertThat(r.usable()).isFalse();
        assertThat(r.reason()).contains("not usable by automation");
    }

    @Test
    void anUnknownQuestionResolvesToNothingRatherThanAnythingElse() {
        AnswerResolution r = service.resolveAll(userId, List.of(
                new EmployerAnswerService.Lookup("Something no employer has ever asked before xyzzy",
                        "SCREENING_QUESTION")))
                .values().iterator().next();

        assertThat(r.usable()).isFalse();
        assertThat(r.reason()).contains("has not been seen before");
    }

    @Test
    void aRecognisedQuestionWithNoStoredAnswerSaysSo() {
        AnswerResolution r = service.resolveAll(userId, List.of(
                new EmployerAnswerService.Lookup(library.get(0).getOriginalText(), "SCREENING_QUESTION")))
                .values().iterator().next();

        assertThat(r.usable()).isFalse();
        assertThat(r.reason()).contains("no answer for it yet");
    }

    // ── gating and degradation ──

    @Test
    void disabledLibraryTouchesNoRepositoryAtAll() {
        EmployerAnswerService off = new EmployerAnswerService(answerRepo,
                new EmployerQuestionService(questionRepo, false), false);

        Map<String, AnswerResolution> results = off.resolveAll(userId, lookupsForWholeForm());

        assertThat(results).hasSize(20);
        assertThat(results.values()).allMatch(r -> !r.usable());
        verify(questionRepo, never()).findTop200ByOrderByLastSeenAtDesc();
        verify(answerRepo, never()).findByUserId(any());
    }

    @Test
    void noLookupsMeansNoQueries() {
        assertThat(service.resolveAll(userId, List.of())).isEmpty();
        verify(questionRepo, never()).findTop200ByOrderByLastSeenAtDesc();
        verify(answerRepo, never()).findByUserId(any());
    }

    @Test
    void aFailedAnswerReadDegradesToNoAnswersRatherThanThrowing() {
        when(answerRepo.findByUserId(userId)).thenThrow(new RuntimeException("db down"));

        Map<String, AnswerResolution> results = service.resolveAll(userId, lookupsForWholeForm());

        assertThat(results).hasSize(20);
        assertThat(results.values()).allMatch(r -> !r.usable());
    }

    @Test
    void aFailedUsageWriteDoesNotUndoASuccessfulResolution() {
        EmployerQuestion q = library.get(0);
        when(answerRepo.findByUserId(userId)).thenReturn(List.of(approvedAnswer(q.getId(), "text")));
        when(answerRepo.saveAll(anyList())).thenThrow(new RuntimeException("write failed"));

        AnswerResolution r = service.resolveAll(userId,
                List.of(new EmployerAnswerService.Lookup(q.getOriginalText(), "SCREENING_QUESTION")))
                .values().iterator().next();

        // Usage counters are bookkeeping about an answer that was legitimately reused.
        assertThat(r.usable()).isTrue();
        assertThat(r.answerText()).isEqualTo("text");
    }

    @Test
    void aNullUserResolvesEverythingToNothing() {
        Map<String, AnswerResolution> results = service.resolveAll(null, lookupsForWholeForm());

        assertThat(results.values()).allMatch(r -> !r.usable());
        verify(answerRepo, never()).findByUserId(any());
    }
}
