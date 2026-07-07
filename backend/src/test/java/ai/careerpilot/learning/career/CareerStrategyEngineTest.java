package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerStrategyEngineTest {

    private CareerProbabilityEngine probabilityEngine;
    private CareerTrajectoryAnalyzer trajectoryAnalyzer;
    private CareerStrategyRepository strategies;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        probabilityEngine = mock(CareerProbabilityEngine.class);
        trajectoryAnalyzer = mock(CareerTrajectoryAnalyzer.class);
        strategies = mock(CareerStrategyRepository.class);
        when(strategies.findByUserId(any())).thenReturn(Optional.empty());
        when(strategies.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void disabledEngineNeverPersists() {
        CareerStrategyEngine engine = new CareerStrategyEngine(probabilityEngine, trajectoryAnalyzer, strategies, false);
        engine.recompute(userId);
        verify(strategies, never()).save(any());
        verifyNoInteractions(probabilityEngine, trajectoryAnalyzer);
    }

    @Test
    void enabledEnginePersistsComputedProbabilitiesAndTrajectory() {
        var probs = new CareerProbabilityEngine.Probabilities(
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.2),
                BigDecimal.valueOf(0.3), BigDecimal.valueOf(0.6));
        when(probabilityEngine.compute(userId)).thenReturn(probs);
        when(trajectoryAnalyzer.recommend(userId)).thenReturn("Focus on Java roles.");

        CareerStrategyEngine engine = new CareerStrategyEngine(probabilityEngine, trajectoryAnalyzer, strategies, true);
        engine.recompute(userId);

        var captor = org.mockito.ArgumentCaptor.forClass(CareerStrategy.class);
        verify(strategies).save(captor.capture());
        assertEquals(BigDecimal.valueOf(0.5), captor.getValue().getCareerSuccessProbability());
        assertEquals("Focus on Java roles.", captor.getValue().getRecommendedTrajectory());
    }

    @Test
    void updatesExistingRowInPlace() {
        CareerStrategy existing = CareerStrategy.builder().userId(userId).build();
        when(strategies.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(probabilityEngine.compute(userId)).thenReturn(new CareerProbabilityEngine.Probabilities(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        when(trajectoryAnalyzer.recommend(userId)).thenReturn("x");

        CareerStrategyEngine engine = new CareerStrategyEngine(probabilityEngine, trajectoryAnalyzer, strategies, true);
        engine.recompute(userId);

        verify(strategies).save(existing);
        assertEquals(BigDecimal.ONE, existing.getOfferProbability());
    }
}
