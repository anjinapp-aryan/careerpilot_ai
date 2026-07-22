package ai.careerpilot.workflow.interview;

import ai.careerpilot.domain.Interview;
import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.repo.InterviewFeedbackRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.InterviewTimelineRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Phase 3A.4 — the interview service ships DARK (disabled → no-op), records append-only, never throws. */
class InterviewServiceTest {

    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final InterviewFeedbackRepository feedback = mock(InterviewFeedbackRepository.class);
    private final InterviewTimelineRepository timeline = mock(InterviewTimelineRepository.class);
    private final InterviewMetrics metrics = new InterviewMetrics();
    private final CareerMemoryService careerMemory = mock(CareerMemoryService.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private InterviewService svc(boolean enabled) {
        return new InterviewService(interviews, feedback, timeline, metrics, careerMemory, enabled);
    }

    @Test
    void disabledIsNoOp() {
        assertThat(svc(false).record(userId, jobId, "TECHNICAL", null, null, null)).isEmpty();
        assertThat(svc(false).addFeedback(UUID.randomUUID(), "great", 5)).isEmpty();
        verifyNoInteractions(interviews);
    }

    @Test
    void recordPersistsInterviewAndTimeline() {
        when(interviews.save(any())).thenAnswer(inv -> {
            Interview r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        assertThat(svc(true).record(userId, jobId, "TECHNICAL", "Jane", 60, Interview.RESULT_SCHEDULED)).isPresent();
        verify(interviews).save(any(Interview.class));
        verify(timeline).save(any());
    }

    @Test
    void recordDefaultsResultToScheduled() {
        when(interviews.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(svc(true).record(userId, jobId, "TECHNICAL", null, null, null))
                .get().extracting(Interview::getResult).isEqualTo(Interview.RESULT_SCHEDULED);
    }

    @Test
    void addFeedbackPersistsWhenEnabled() {
        when(feedback.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(svc(true).addFeedback(UUID.randomUUID(), "solid", 4)).isPresent();
    }

    @Test
    void neverThrowsOnRepoFailure() {
        when(interviews.save(any())).thenThrow(new RuntimeException("db down"));
        assertThat(svc(true).record(userId, jobId, "TECHNICAL", null, null, null)).isEmpty();
    }

    @Test
    void markDetectedCountsSignal() {
        svc(true).markDetected();
        assertThat(metrics.snapshot().get("interviewDetected")).isEqualTo(1L);
    }

    // ── Phase 7.17.2 — closes the "result is permanently uncomputable" data gap ──

    @Test
    void highRatingFeedbackInfersPassedResult() {
        UUID interviewId = UUID.randomUUID();
        Interview interview = Interview.builder().id(interviewId).userId(userId).jobId(jobId)
                .interviewType("TECHNICAL").result(Interview.RESULT_SCHEDULED).build();
        when(interviews.findById(interviewId)).thenReturn(java.util.Optional.of(interview));
        when(feedback.save(any())).thenAnswer(inv -> inv.getArgument(0));

        svc(true).addFeedback(interviewId, "went great", 5);

        assertThat(interview.getResult()).isEqualTo(Interview.RESULT_PASSED);
        verify(interviews).save(interview);
    }

    @Test
    void lowRatingFeedbackInfersFailedResult() {
        UUID interviewId = UUID.randomUUID();
        Interview interview = Interview.builder().id(interviewId).userId(userId).jobId(jobId)
                .interviewType("TECHNICAL").result(Interview.RESULT_SCHEDULED).build();
        when(interviews.findById(interviewId)).thenReturn(java.util.Optional.of(interview));
        when(feedback.save(any())).thenAnswer(inv -> inv.getArgument(0));

        svc(true).addFeedback(interviewId, "bombed it", 1);

        assertThat(interview.getResult()).isEqualTo(Interview.RESULT_FAILED);
    }

    @Test
    void ambiguousMidRatingNeverGuessesAResult() {
        UUID interviewId = UUID.randomUUID();
        Interview interview = Interview.builder().id(interviewId).userId(userId).jobId(jobId)
                .interviewType("TECHNICAL").result(Interview.RESULT_SCHEDULED).build();
        when(interviews.findById(interviewId)).thenReturn(java.util.Optional.of(interview));
        when(feedback.save(any())).thenAnswer(inv -> inv.getArgument(0));

        svc(true).addFeedback(interviewId, "mixed bag", 3);

        assertThat(interview.getResult()).isEqualTo(Interview.RESULT_SCHEDULED);
        verify(interviews, never()).save(interview);
    }

    @Test
    void nullRatingNeverInfersAResult() {
        UUID interviewId = UUID.randomUUID();
        Interview interview = Interview.builder().id(interviewId).userId(userId).jobId(jobId)
                .interviewType("TECHNICAL").result(Interview.RESULT_SCHEDULED).build();
        when(interviews.findById(interviewId)).thenReturn(java.util.Optional.of(interview));
        when(feedback.save(any())).thenAnswer(inv -> inv.getArgument(0));

        svc(true).addFeedback(interviewId, "no rating given", null);

        assertThat(interview.getResult()).isEqualTo(Interview.RESULT_SCHEDULED);
    }

    @Test
    void cancelledResultIsNeverOverwrittenByFeedback() {
        UUID interviewId = UUID.randomUUID();
        Interview interview = Interview.builder().id(interviewId).userId(userId).jobId(jobId)
                .interviewType("TECHNICAL").result(Interview.RESULT_CANCELLED).build();
        when(interviews.findById(interviewId)).thenReturn(java.util.Optional.of(interview));
        when(feedback.save(any())).thenAnswer(inv -> inv.getArgument(0));

        svc(true).addFeedback(interviewId, "n/a", 5);

        assertThat(interview.getResult()).isEqualTo(Interview.RESULT_CANCELLED);
    }
}
