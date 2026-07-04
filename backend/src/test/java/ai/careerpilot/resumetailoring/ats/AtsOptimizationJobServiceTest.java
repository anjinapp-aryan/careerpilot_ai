package ai.careerpilot.resumetailoring.ats;

import ai.careerpilot.domain.AtsOptimizationJob;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.repo.AtsOptimizationJobRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import ai.careerpilot.resumetailoring.event.AtsOptimizedEvent;
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
 * Phase 2D.2 — {@link AtsOptimizationJobService} owns the QUEUED -&gt; RUNNING -&gt;
 * SUCCEEDED/FAILED lifecycle on the bounded executor, mirroring {@code
 * ResumeTailoringJobServiceTest} exactly. A missing tailored resume or a saturated queue must both
 * fail immediately.
 */
class AtsOptimizationJobServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();

    private final Map<UUID, AtsOptimizationJob> store = new ConcurrentHashMap<>();
    private AtsOptimizationJobRepository jobs;
    private ResumeAtsAnalysisRepository analyses;
    private ResumeTailoringRepository tailorings;
    private AtsOptimizationService optimization;
    private ThreadPoolTaskExecutor executor;
    private ApplicationEventPublisher events;

    private AtsOptimizationJobService service(int corePoolSize, int maxPoolSize, int queueCapacity) {
        store.clear();
        jobs = mock(AtsOptimizationJobRepository.class);
        analyses = mock(ResumeAtsAnalysisRepository.class);
        tailorings = mock(ResumeTailoringRepository.class);
        optimization = mock(AtsOptimizationService.class);
        events = mock(ApplicationEventPublisher.class);

        when(jobs.save(any(AtsOptimizationJob.class))).thenAnswer(inv -> {
            AtsOptimizationJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(UUID.randomUUID());
            store.put(j.getId(), j);
            return j;
        });
        when(jobs.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get((UUID) inv.getArgument(0))));

        ResumeTailoring tailoring = ResumeTailoring.builder().id(tailoringId).userId(userId).jobId(jobId)
                .tailoringVersion(1).status(ResumeTailoring.STATUS_GENERATED).build();
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.of(tailoring));

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("test-ats-optimize-");
        executor.initialize();

        return new AtsOptimizationJobService(jobs, analyses, tailorings, optimization, executor, events);
    }

    @AfterEach
    void shutdown() {
        if (executor != null) executor.shutdown();
    }

    private AtsOptimizationJob awaitTerminal(UUID jobRowId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            AtsOptimizationJob j = store.get(jobRowId);
            if (j != null && (AtsOptimizationJob.STATUS_SUCCEEDED.equals(j.getStatus())
                    || AtsOptimizationJob.STATUS_FAILED.equals(j.getStatus()))) {
                return j;
            }
            Thread.sleep(10);
        }
        fail("job did not reach a terminal state within timeout: " + store.get(jobRowId));
        return null;
    }

    @Test
    void enqueueTransitionsQueuedToSucceededOnASuccessfulAnalysis() throws InterruptedException {
        AtsOptimizationJobService service = service(1, 2, 10);
        UUID resultId = UUID.randomUUID();
        ResumeAtsAnalysis result = ResumeAtsAnalysis.builder().id(resultId).userId(userId).jobId(jobId)
                .resumeTailoringId(tailoringId).atsScore(80).status(ResumeAtsAnalysis.STATUS_GENERATED).build();
        when(optimization.analyze(userId, jobId)).thenReturn(Optional.of(result));

        AtsOptimizationJob job = service.enqueue(userId, jobId, AtsOptimizationJob.SOURCE_MANUAL);
        assertEquals(AtsOptimizationJob.STATUS_QUEUED, job.getStatus());
        assertEquals(tailoringId, job.getResumeTailoringId());

        AtsOptimizationJob terminal = awaitTerminal(job.getId());
        assertEquals(AtsOptimizationJob.STATUS_SUCCEEDED, terminal.getStatus());
        assertEquals(resultId, terminal.getAtsAnalysisId());
        assertNotNull(terminal.getStartedAt());
        assertNotNull(terminal.getCompletedAt());
        verify(events).publishEvent(new AtsOptimizedEvent(userId, jobId, tailoringId, resultId));
    }

    @Test
    void doesNotPublishAtsOptimizedEventWhenAnalysisFails() throws InterruptedException {
        AtsOptimizationJobService service = service(1, 2, 10);
        when(optimization.analyze(userId, jobId)).thenReturn(Optional.empty());

        AtsOptimizationJob job = service.enqueue(userId, jobId, AtsOptimizationJob.SOURCE_MANUAL);
        awaitTerminal(job.getId());

        verify(events, never()).publishEvent(any());
    }

    @Test
    void enqueueTransitionsToFailedWhenAnalysisReturnsEmpty() throws InterruptedException {
        AtsOptimizationJobService service = service(1, 2, 10);
        when(optimization.analyze(userId, jobId)).thenReturn(Optional.empty());

        AtsOptimizationJob job = service.enqueue(userId, jobId, AtsOptimizationJob.SOURCE_MANUAL);
        AtsOptimizationJob terminal = awaitTerminal(job.getId());

        assertEquals(AtsOptimizationJob.STATUS_FAILED, terminal.getStatus());
        assertNotNull(terminal.getErrorReason());
    }

    @Test
    void enqueueTransitionsToFailedWhenAnalysisThrows() throws InterruptedException {
        AtsOptimizationJobService service = service(1, 2, 10);
        when(optimization.analyze(userId, jobId)).thenThrow(new RuntimeException("boom"));

        AtsOptimizationJob job = service.enqueue(userId, jobId, AtsOptimizationJob.SOURCE_MANUAL);
        AtsOptimizationJob terminal = awaitTerminal(job.getId());

        assertEquals(AtsOptimizationJob.STATUS_FAILED, terminal.getStatus());
        assertTrue(terminal.getErrorReason().contains("boom"));
    }

    @Test
    void enqueueFailsImmediatelyWhenNoTailoredResumeExistsYet() {
        AtsOptimizationJobService service = service(1, 2, 10);
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId)).thenReturn(Optional.empty());

        AtsOptimizationJob job = service.enqueue(userId, jobId, AtsOptimizationJob.SOURCE_MANUAL);

        assertEquals(AtsOptimizationJob.STATUS_FAILED, job.getStatus());
        assertNotNull(job.getErrorReason());
        assertNotNull(job.getCompletedAt());
        verifyNoInteractions(optimization);
    }

    @Test
    void aSaturatedQueueFailsTheJobImmediatelyInsteadOfBlockingOrGrowingUnbounded() throws InterruptedException {
        AtsOptimizationJobService service = service(1, 1, 0);
        CountDownLatch release = new CountDownLatch(1);
        when(optimization.analyze(userId, jobId)).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        service.enqueue(userId, jobId, AtsOptimizationJob.SOURCE_MANUAL);
        Thread.sleep(50);

        AtsOptimizationJob rejected = service.enqueue(userId, jobId, AtsOptimizationJob.SOURCE_MANUAL);

        assertEquals(AtsOptimizationJob.STATUS_FAILED, rejected.getStatus());
        assertEquals("queue at capacity", rejected.getErrorReason());
        release.countDown();
    }

    @Test
    void statusIsOwnershipChecked() {
        AtsOptimizationJobService service = service(1, 1, 5);
        UUID jobRowId = UUID.randomUUID();
        service.status(jobRowId, userId);
        verify(jobs).findByIdAndUserId(jobRowId, userId);
    }

    @Test
    void resultOfIsEmptyWhenNoAtsAnalysisIdIsSet() {
        AtsOptimizationJobService service = service(1, 1, 5);
        AtsOptimizationJob job = AtsOptimizationJob.builder().id(UUID.randomUUID()).build();
        assertTrue(service.resultOf(job).isEmpty());
        verifyNoInteractions(analyses);
    }
}
