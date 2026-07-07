package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.repo.ResumeLearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeLearningServiceTest {

    private ResumePerformanceAnalyzer analyzer;
    private ResumeLearningRepository resumeLearning;
    private ResumeLearningService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        analyzer = mock(ResumePerformanceAnalyzer.class);
        resumeLearning = mock(ResumeLearningRepository.class);
        service = new ResumeLearningService(analyzer, resumeLearning);
        when(resumeLearning.findByUserIdAndResumeVersion(any(), any())).thenReturn(Optional.empty());
        when(resumeLearning.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void noStatsPersistsNothing() {
        when(analyzer.analyze(userId)).thenReturn(List.of());
        service.recompute(userId);
        verify(resumeLearning, never()).save(any());
    }

    @Test
    void bestVersionIsHighestOfferRate() {
        var v1 = new ResumePerformanceAnalyzer.VersionStats("v1", 10, 3, 1,
                BigDecimal.valueOf(70), BigDecimal.valueOf(0.3), BigDecimal.valueOf(0.1));
        var v2 = new ResumePerformanceAnalyzer.VersionStats("v2", 10, 4, 3,
                BigDecimal.valueOf(85), BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.3));
        when(analyzer.analyze(userId)).thenReturn(List.of(v1, v2));

        service.recompute(userId);

        var captor = org.mockito.ArgumentCaptor.forClass(ResumeLearning.class);
        verify(resumeLearning, times(2)).save(captor.capture());
        var v2Row = captor.getAllValues().stream().filter(r -> r.getResumeVersion().equals("v2")).findFirst().orElseThrow();
        assertTrue(v2Row.isBestVersion());
        var v1Row = captor.getAllValues().stream().filter(r -> r.getResumeVersion().equals("v1")).findFirst().orElseThrow();
        assertFalse(v1Row.isBestVersion());
    }

    @Test
    void updatesExistingRowInPlace() {
        ResumeLearning existing = ResumeLearning.builder().userId(userId).resumeVersion("v1").build();
        when(resumeLearning.findByUserIdAndResumeVersion(userId, "v1")).thenReturn(Optional.of(existing));
        var stats = new ResumePerformanceAnalyzer.VersionStats("v1", 5, 1, 0,
                BigDecimal.valueOf(60), BigDecimal.valueOf(0.2), BigDecimal.ZERO);
        when(analyzer.analyze(userId)).thenReturn(List.of(stats));

        service.recompute(userId);

        verify(resumeLearning).save(existing);
        assertEquals(5, existing.getApplications());
    }
}
