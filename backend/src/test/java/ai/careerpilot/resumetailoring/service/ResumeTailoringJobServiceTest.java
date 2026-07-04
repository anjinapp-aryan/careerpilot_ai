package ai.careerpilot.resumetailoring.service;

import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.domain.ResumeTailoringJob;
import ai.careerpilot.repo.ResumeTailoringJobRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import ai.careerpilot.resumetailoring.event.ResumeTailoredEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.1.1 — {@link ResumeTailoringJobService} owns the QUEUED -&gt; RUNNING -&gt;
 * SUCCEEDED/FAILED lifecycle on the bounded executor, in front of the unchanged {@link
 * ResumeTailoringService#tailor}/{@link ResumeTailoringService#rebuild}. A saturated queue must
 * fail immediately (never block the caller or grow unbounded).
 */
class ResumeTailoringJobServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private final Map<UUID, ResumeTailoringJob> store = new ConcurrentHashMap<>();
    private ResumeTailoringJobRepository jobs;
    private ResumeTailoringRepository tailorings;
    private ResumeTailoringService tailoring;
    private ThreadPoolTaskExecutor executor;
    private ApplicationEventPublisher events;

    private ResumeTailoringJobService service(int corePoolSize, int maxPoolSize, int queueCapacity) {
        store.clear();
        jobs = mock(ResumeTailoringJobRepository.class);
        tailorings = mock(ResumeTailoringRepository.class);
        tailoring = mock(ResumeTailoringService.class);
        events = mock(ApplicationEventPublisher.class);

        when(jobs.save(any(ResumeTailoringJob.class))).thenAnswer(inv -> {
            ResumeTailoringJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(UUID.randomUUID());
            store.put(j.getId(), j);
            return j;
        });
        when(jobs.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("test-resume-tailor-");
        executor.initialize();

        return new ResumeTailoringJobService(jobs, tailorings, tailoring, executor, events);
    }

    @AfterEach
    void shutdown() {
        if (executor != null) executor.shutdown();
    }

    private ResumeTailoringJob awaitTerminal(UUID jobRowId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            ResumeTailoringJob j = store.get(jobRowId);
            if (j != null && (ResumeTailoringJob.STATUS_SUCCEEDED.equals(j.getStatus())
                    || ResumeTailoringJob.STATUS_FAILED.equals(j.getStatus()))) {
                return j;
            }
            Thread.sleep(10);
        }
        fail("job did not reach a terminal state within timeout: " + store.get(jobRowId));
        return null;
    }

    @Test
    void enqueueTransitionsQueuedToSucceededOnASuccessfulGeneration() throws InterruptedException {
        ResumeTailoringJobService service = service(1, 2, 10);
        UUID resultId = UUID.randomUUID();
        ResumeTailoring result = ResumeTailoring.builder().id(resultId).userId(userId).jobId(jobId)
                .tailoringVersion(1).status(ResumeTailoring.STATUS_GENERATED).build();
        when(tailoring.tailor(userId, jobId, null)).thenReturn(Optional.of(result));

        ResumeTailoringJob job = service.enqueue(userId, jobId, null, ResumeTailoringJob.SOURCE_MANUAL);
        assertEquals(ResumeTailoringJob.STATUS_QUEUED, job.getStatus());

        ResumeTailoringJob terminal = awaitTerminal(job.getId());
        assertEquals(ResumeTailoringJob.STATUS_SUCCEEDED, terminal.getStatus());
        assertEquals(resultId, terminal.getResumeTailoringId());
        assertNotNull(terminal.getStartedAt());
        assertNotNull(terminal.getCompletedAt());
        verify(events).publishEvent(new ResumeTailoredEvent(userId, jobId, resultId, null));
    }

    @Test
    void doesNotPublishResumeTailoredEventWhenTailoringFails() throws InterruptedException {
        ResumeTailoringJobService service = service(1, 2, 10);
        when(tailoring.tailor(userId, jobId, null)).thenReturn(Optional.empty());

        ResumeTailoringJob job = service.enqueue(userId, jobId, null, ResumeTailoringJob.SOURCE_MANUAL);
        awaitTerminal(job.getId());

        verify(events, never()).publishEvent(any());
    }

    @Test
    void enqueueTransitionsToFailedWhenTailoringReturnsEmpty() throws InterruptedException {
        ResumeTailoringJobService service = service(1, 2, 10);
        when(tailoring.tailor(userId, jobId, null)).thenReturn(Optional.empty());

        ResumeTailoringJob job = service.enqueue(userId, jobId, null, ResumeTailoringJob.SOURCE_MANUAL);
        ResumeTailoringJob terminal = awaitTerminal(job.getId());

        assertEquals(ResumeTailoringJob.STATUS_FAILED, terminal.getStatus());
        assertNotNull(terminal.getErrorReason());
    }

    @Test
    void enqueueTransitionsToFailedWhenTailoringThrows() throws InterruptedException {
        ResumeTailoringJobService service = service(1, 2, 10);
        when(tailoring.tailor(userId, jobId, null)).thenThrow(new RuntimeException("boom"));

        ResumeTailoringJob job = service.enqueue(userId, jobId, null, ResumeTailoringJob.SOURCE_MANUAL);
        ResumeTailoringJob terminal = awaitTerminal(job.getId());

        assertEquals(ResumeTailoringJob.STATUS_FAILED, terminal.getStatus());
        assertTrue(terminal.getErrorReason().contains("boom"));
    }

    @Test
    void enqueueRebuildCallsRebuildNotTailor() throws InterruptedException {
        ResumeTailoringJobService service = service(1, 2, 10);
        UUID resultId = UUID.randomUUID();
        ResumeTailoring result = ResumeTailoring.builder().id(resultId).userId(userId).jobId(jobId)
                .tailoringVersion(2).status(ResumeTailoring.STATUS_GENERATED).build();
        when(tailoring.rebuild(userId, jobId)).thenReturn(Optional.of(result));

        ResumeTailoringJob job = service.enqueueRebuild(userId, jobId);
        awaitTerminal(job.getId());

        verify(tailoring).rebuild(userId, jobId);
        verify(tailoring, never()).tailor(any(), any(), any());
    }

    @Test
    void aSaturatedQueueFailsTheJobImmediatelyInsteadOfBlockingOrGrowingUnbounded() throws InterruptedException {
        // core=1, max=1, queue=0: one running task fully occupies the pool, so a second submission
        // has nowhere to go and must be rejected synchronously.
        ResumeTailoringJobService service = service(1, 1, 0);
        CountDownLatch release = new CountDownLatch(1);
        when(tailoring.tailor(userId, jobId, null)).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        // Occupy the sole thread.
        service.enqueue(userId, jobId, null, ResumeTailoringJob.SOURCE_MANUAL);
        // Give the executor a moment to actually pick up the first task before saturating.
        Thread.sleep(50);

        ResumeTailoringJob rejected = service.enqueue(userId, jobId, null, ResumeTailoringJob.SOURCE_MANUAL);

        assertEquals(ResumeTailoringJob.STATUS_FAILED, rejected.getStatus());
        assertEquals("queue at capacity", rejected.getErrorReason());
        assertNotNull(rejected.getCompletedAt());

        release.countDown();
    }

    @Test
    void statusIsOwnershipChecked() {
        ResumeTailoringJobService service = service(1, 1, 5);
        UUID jobRowId = UUID.randomUUID();
        service.status(jobRowId, userId);
        verify(jobs).findByIdAndUserId(jobRowId, userId);
    }

    @Test
    void resultOfIsEmptyWhenNoResumeTailoringIdIsSet() {
        ResumeTailoringJobService service = service(1, 1, 5);
        ResumeTailoringJob job = ResumeTailoringJob.builder().id(UUID.randomUUID()).build();
        assertTrue(service.resultOf(job).isEmpty());
        verifyNoInteractions(tailorings);
    }
}
