package ai.careerpilot.learning;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningEventServiceTest {

    private LearningEventRepository events;
    private LearningMetricsLogRepository metricsLog;
    private JobRepository jobs;
    private LearningMetrics metrics;
    private ApplicationEventPublisher publisher;
    private LearningEventService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(LearningEventRepository.class);
        metricsLog = mock(LearningMetricsLogRepository.class);
        jobs = mock(JobRepository.class);
        metrics = new LearningMetrics();
        publisher = mock(ApplicationEventPublisher.class);
        service = new LearningEventService(events, metricsLog, jobs, metrics, publisher);
        when(events.save(any())).thenAnswer(inv -> {
            LearningEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    void recordsPlainEventWithoutJobEnrichment() {
        service.record(LearningEventType.APPLICATION_SUBMITTED, null, userId, null, null, null);
        var captor = org.mockito.ArgumentCaptor.forClass(LearningEvent.class);
        verify(events).save(captor.capture());
        assertEquals(userId, captor.getValue().getUserId());
        assertNull(captor.getValue().getCompany());
    }

    @Test
    void enrichesFromJobWhenJobIdProvided() {
        Job job = Job.builder().id(jobId).title("SWE").company("Acme").country("India")
                .jobFamily("TECH").skills("Java,Spring")
                .salaryMin(BigDecimal.valueOf(80000)).salaryMax(BigDecimal.valueOf(120000)).build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));

        service.record(LearningEventType.APPLICATION_SUBMITTED, null, userId, jobId, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(LearningEvent.class);
        verify(events).save(captor.capture());
        assertEquals("Acme", captor.getValue().getCompany());
        assertEquals("India", captor.getValue().getCountry());
        assertEquals("TECH", captor.getValue().getRoleFamily());
        assertEquals("100K_150K", captor.getValue().getSalaryBand());
    }

    @Test
    void missingJobIsToleratedNoEnrichment() {
        when(jobs.findById(jobId)).thenReturn(Optional.empty());
        service.record(LearningEventType.APPLICATION_SUBMITTED, null, userId, jobId, null, null);
        var captor = org.mockito.ArgumentCaptor.forClass(LearningEvent.class);
        verify(events).save(captor.capture());
        assertNull(captor.getValue().getCompany());
    }

    @Test
    void publishesRecordedEventAfterSave() {
        service.record(LearningEventType.OFFER_RECEIVED, null, userId, null, null, null);
        var captor = org.mockito.ArgumentCaptor.forClass(LearningEventRecordedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(LearningEventType.OFFER_RECEIVED, captor.getValue().eventType());
        assertEquals(userId, captor.getValue().userId());
    }

    @Test
    void incrementsEventCaptureMetric() {
        service.record(LearningEventType.RESUME_SELECTED, null, userId, null, "v1", null);
        assertEquals(1, metrics.total("EVENT_CAPTURE"));
    }

    @Test
    void logsMetricsAuditRowOnSuccess() {
        service.record(LearningEventType.RESUME_SELECTED, null, userId, null, "v1", null);
        verify(metricsLog).save(any());
    }
}
