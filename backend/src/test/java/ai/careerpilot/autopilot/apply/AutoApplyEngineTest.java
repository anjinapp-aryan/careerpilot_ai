package ai.careerpilot.autopilot.apply;

import ai.careerpilot.autopilot.provider.ApplicationProvider;
import ai.careerpilot.autopilot.provider.ApplicationProviderRegistry;
import ai.careerpilot.autopilot.provider.SubmissionResult;
import ai.careerpilot.autopilot.provider.SubmissionStatus;
import ai.careerpilot.domain.ApplicationSubmission;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import ai.careerpilot.repo.ApplicationSubmissionRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutoApplyEngineTest {

    private ApplicationProviderRegistry registry;
    private JobRepository jobs;
    private ApplicationSubmissionRepository submissions;
    private ApplicationEventPublisher publisher;
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registry = mock(ApplicationProviderRegistry.class);
        jobs = mock(JobRepository.class);
        submissions = mock(ApplicationSubmissionRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        when(submissions.save(any())).thenAnswer(inv -> {
            ApplicationSubmission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
    }

    private AutoApplyEngine engine(boolean enabled) {
        return new AutoApplyEngine(registry, jobs, submissions, publisher, enabled);
    }

    private void stubJobWithUrl(String url) {
        when(jobs.findById(jobId)).thenReturn(Optional.of(Job.builder().id(jobId).company("Acme").sourceUrl(url).build()));
    }

    @Test
    void disabledIsNoOp() {
        assertTrue(engine(false).apply(userId, jobId, null).isEmpty());
        verifyNoInteractions(registry, submissions, publisher);
    }

    @Test
    void noProviderRecordsHumanReviewAndPublishesNothing() {
        stubJobWithUrl(null);
        when(registry.resolve(any())).thenReturn(Optional.empty());
        var out = engine(true).apply(userId, jobId, null);
        assertEquals(SubmissionStatus.HUMAN_REVIEW.name(), out.orElseThrow().getStatus());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void unconfiguredProviderRecordsHumanReview() {
        stubJobWithUrl("https://boards.greenhouse.io/acme/1");
        ApplicationProvider p = mock(ApplicationProvider.class);
        when(p.name()).thenReturn("greenhouse");
        when(p.autoSubmitConfigured()).thenReturn(false);
        when(registry.resolve(any())).thenReturn(Optional.of(p));

        var out = engine(true).apply(userId, jobId, null);
        assertEquals(SubmissionStatus.HUMAN_REVIEW.name(), out.orElseThrow().getStatus());
        assertEquals("greenhouse", out.get().getProvider());
        verify(p, never()).submit(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void genuineSubmittedPublishesApplicationSubmittedEvent() {
        stubJobWithUrl("https://boards.greenhouse.io/acme/1");
        ApplicationProvider p = mock(ApplicationProvider.class);
        when(p.name()).thenReturn("greenhouse");
        when(p.autoSubmitConfigured()).thenReturn(true);
        when(p.submit(any())).thenReturn(new SubmissionResult(SubmissionStatus.SUBMITTED, "greenhouse", "ext-1", "ok"));
        when(registry.resolve(any())).thenReturn(Optional.of(p));

        var out = engine(true).apply(userId, jobId, null);
        assertEquals(SubmissionStatus.SUBMITTED.name(), out.orElseThrow().getStatus());
        verify(publisher).publishEvent(any(ApplicationSubmittedEvent.class));
    }

    @Test
    void providerThrowingRecordsFailedNeverPublishes() {
        stubJobWithUrl("https://boards.greenhouse.io/acme/1");
        ApplicationProvider p = mock(ApplicationProvider.class);
        when(p.name()).thenReturn("greenhouse");
        when(p.autoSubmitConfigured()).thenReturn(true);
        when(p.submit(any())).thenThrow(new RuntimeException("boom"));
        when(registry.resolve(any())).thenReturn(Optional.of(p));

        var out = engine(true).apply(userId, jobId, null);
        assertEquals(SubmissionStatus.FAILED.name(), out.orElseThrow().getStatus());
        verify(publisher, never()).publishEvent(any());
    }
}
