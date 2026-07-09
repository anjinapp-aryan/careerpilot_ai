package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumePerformanceAnalyzerTest {

    private LearningEventRepository events;
    private ResumeAtsAnalysisRepository atsAnalyses;
    private ResumePerformanceAnalyzer analyzer;
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(LearningEventRepository.class);
        atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
        analyzer = new ResumePerformanceAnalyzer(events, atsAnalyses);
        when(atsAnalyses.findByUserIdAndResumeTailoringId(any(), any())).thenReturn(List.of());
    }

    private LearningEvent event(LearningEventType type, UUID job, String resumeVersion) {
        return LearningEvent.builder().userId(userId).jobId(job).eventType(type.name()).resumeVersion(resumeVersion).build();
    }

    @Test
    void noHistoryProducesNoStats() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        assertTrue(analyzer.analyze(userId).isEmpty());
    }

    @Test
    void attributesApplicationToResumeVersionViaJobId() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                event(LearningEventType.RESUME_SELECTED, jobId, tailoringId.toString()),
                event(LearningEventType.APPLICATION_SUBMITTED, jobId, null),
                event(LearningEventType.INTERVIEW_SCHEDULED, jobId, null),
                event(LearningEventType.OFFER_RECEIVED, jobId, null)));

        var stats = analyzer.analyze(userId);
        assertEquals(1, stats.size());
        var s = stats.get(0);
        assertEquals(tailoringId.toString(), s.resumeVersion());
        assertEquals(1, s.applications());
        assertEquals(1, s.interviews());
        assertEquals(1, s.offers());
    }

    @Test
    void eventsForUnmappedJobsAreIgnored() {
        UUID otherJob = UUID.randomUUID();
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                event(LearningEventType.APPLICATION_SUBMITTED, otherJob, null)));
        assertTrue(analyzer.analyze(userId).isEmpty());
    }

    @Test
    void atsScoreAverageComesFromAtsAnalysisRepository() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                event(LearningEventType.RESUME_SELECTED, jobId, tailoringId.toString()),
                event(LearningEventType.APPLICATION_SUBMITTED, jobId, null)));
        ResumeAtsAnalysis a1 = mock(ResumeAtsAnalysis.class);
        when(a1.getAtsScore()).thenReturn(80);
        ResumeAtsAnalysis a2 = mock(ResumeAtsAnalysis.class);
        when(a2.getAtsScore()).thenReturn(90);
        when(atsAnalyses.findByUserIdAndResumeTailoringId(userId, tailoringId)).thenReturn(List.of(a1, a2));

        var stats = analyzer.analyze(userId);
        assertEquals(0, new java.math.BigDecimal("85.00").compareTo(stats.get(0).atsScoreAvg()));
    }

    @Test
    void nonUuidResumeVersionSkipsAtsLookupGracefully() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                event(LearningEventType.RESUME_SELECTED, jobId, "not-a-uuid"),
                event(LearningEventType.APPLICATION_SUBMITTED, jobId, null)));
        var stats = analyzer.analyze(userId);
        assertNull(stats.get(0).atsScoreAvg());
    }
}
