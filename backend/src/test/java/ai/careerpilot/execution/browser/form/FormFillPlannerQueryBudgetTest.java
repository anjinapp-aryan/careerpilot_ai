package ai.careerpilot.execution.browser.form;

import ai.careerpilot.domain.User;
import ai.careerpilot.employerquestion.EmployerAnswerService;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.CandidateAtsProfileRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.ats.CandidateAtsProfileService;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Work Item 1 — the query budget for planning one form, pinned at the planner level.
 *
 * <p>This is the test that would have failed before the fix. Planning a 20-field form used to issue
 * 40 repository calls — two per field, one of which pulled the entire 200-row question library down
 * again each time. Measured across 106 live validations, planning averaged 11,206 ms against 11 ms
 * for discovery, making it 67% of a 16.8 s page validation.
 *
 * <p>The rule being enforced is simple and checkable: <b>no repository call may happen inside the
 * per-field loop.</b>
 */
class FormFillPlannerQueryBudgetTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private UserRepository users;
    private CandidateProfileRepository profiles;
    private ApplicationSubmissionAnswerRepository answers;
    private EmployerAnswerService library;
    private FormFillPlanner planner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        answers = mock(ApplicationSubmissionAnswerRepository.class);
        library = mock(EmployerAnswerService.class);

        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
        when(library.isEnabled()).thenReturn(true);
        when(library.resolveAll(any(), anyList())).thenReturn(Map.of());

        ObjectProvider<EmployerAnswerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(library);

        FieldClassifier classifier = new FieldClassifier(new QuestionDetectionService());
        planner = new FormFillPlanner(classifier, new AnswerResolver(
                new FieldMappingService(users, profiles,
                        new CandidateAtsProfileService(mock(CandidateAtsProfileRepository.class), false)),
                users, answers, provider));
    }

    /** A realistic screening-heavy form: 20 labelled, fillable text controls. */
    private List<DiscoveredField> twentyFieldForm() {
        List<DiscoveredField> fields = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("selector", "#q" + i);
            map.put("tag", "input");
            map.put("type", "text");
            map.put("label", "Screening question number " + i + "?");
            map.put("required", false);
            fields.add(FormDiscoveryScript.parse(List.of(map)).get(0));
        }
        return fields;
    }

    @Test
    @DisplayName("planning a 20-field form calls the employer library exactly once")
    void twentyFieldsCostOneLibraryCall() {
        planner.plan(twentyFieldForm(), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));

        verify(library, times(1)).resolveAll(any(), anyList());
    }

    @Test
    @DisplayName("the per-field library method is unreachable from planning")
    void thePerFieldLibraryMethodIsNeverCalled() {
        planner.plan(twentyFieldForm(), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));

        // The N+1 itself: resolve(user, text, field) was invoked once per field.
        verify(library, org.mockito.Mockito.never())
                .resolve(any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("every other source is loaded up front too, not per field")
    void allSourcesAreLoadedUpFront() {
        planner.plan(twentyFieldForm(), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));

        // `users.findById` is invoked twice per form — once by FieldMappingService.map() and once
        // by loadContext for the first/last name split, which deliberately reads the raw User
        // record rather than the mapper's output. Both happen before the loop, which is the
        // property that matters; a fixed 2 is not an N+1. Asserted exactly so a future edit that
        // moves either call into the loop fails here.
        verify(users, times(2)).findById(userId);
        verify(profiles, times(1)).findByUserId(userId);
        verify(answers, times(1)).findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Test
    @DisplayName("query count does not grow with the number of fields")
    void queryCountIsIndependentOfFormSize() {
        planner.plan(twentyFieldForm().subList(0, 3), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));
        planner.plan(twentyFieldForm(), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));

        // A 3-field form and a 20-field form cost the same. Before the fix this would have been
        // 3 + 20 library calls; it is now 1 + 1.
        verify(library, times(2)).resolveAll(any(), anyList());
        verify(profiles, times(2)).findByUserId(userId);
    }

    @Test
    @DisplayName("the whole form's lookups are gathered before any resolution")
    void everyFillableLabelledFieldIsPresentedInOneBatch() {
        planner.plan(twentyFieldForm(), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmployerAnswerService.Lookup>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(library).resolveAll(any(), captor.capture());
        assertThat(captor.getValue()).hasSize(20);
        assertThat(captor.getValue().get(0).questionText()).isEqualTo("Screening question number 0?");
    }

    @Test
    void aDisabledLibraryIsNotCalledAtAll() {
        when(library.isEnabled()).thenReturn(false);

        planner.plan(twentyFieldForm(), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));

        verify(library, org.mockito.Mockito.never()).resolveAll(any(), anyList());
    }

    @Test
    void planningStillProducesTheSameShapeOfResult() {
        // Guard against "made it fast, broke what it does".
        FormFillPlan plan = planner.plan(twentyFieldForm(), userId, sessionId,
                new FormFillPlanner.Documents(true, true, null));

        assertThat(plan.fills().size() + plan.unresolved().size()).isEqualTo(20);
    }
}
