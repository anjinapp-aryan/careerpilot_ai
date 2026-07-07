package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerLearning;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.repo.CareerLearningRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerLearningEngineTest {

    private SuccessPatternRepository successPatterns;
    private CareerLearningRepository careerLearning;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        successPatterns = mock(SuccessPatternRepository.class);
        careerLearning = mock(CareerLearningRepository.class);
        when(careerLearning.findByUserIdAndDimensionAndDimensionKey(any(), any(), any())).thenReturn(Optional.empty());
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(any(), any())).thenReturn(List.of());
    }

    @Test
    void disabledEngineNeverPersists() {
        CareerLearningEngine engine = new CareerLearningEngine(successPatterns, careerLearning, false);
        engine.recompute(userId);
        verify(careerLearning, never()).save(any());
        assertFalse(engine.isEnabled());
    }

    @Test
    void companySuccessPatternBecomesCareerLearningRow() {
        SuccessPattern sp = SuccessPattern.builder().userId(userId).dimension(SuccessPattern.DIM_COMPANY)
                .dimensionKey("Google").applications(20).successRate(new BigDecimal("0.4000")).build();
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_COMPANY))
                .thenReturn(List.of(sp));

        CareerLearningEngine engine = new CareerLearningEngine(successPatterns, careerLearning, true);
        engine.recompute(userId);

        var captor = org.mockito.ArgumentCaptor.forClass(CareerLearning.class);
        verify(careerLearning, atLeastOnce()).save(captor.capture());
        var googleRow = captor.getAllValues().stream().filter(r -> "Google".equals(r.getDimensionKey())).findFirst().orElseThrow();
        assertEquals(CareerLearning.DIM_COMPANY, googleRow.getDimension());
        assertEquals(20, googleRow.getSampleSize());
    }

    @Test
    void roleDimensionIsNotModeledInCareerLearning() {
        SuccessPattern sp = SuccessPattern.builder().userId(userId).dimension(SuccessPattern.DIM_ROLE)
                .dimensionKey("Backend").applications(5).successRate(BigDecimal.ONE).build();
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_ROLE))
                .thenReturn(List.of(sp));
        CareerLearningEngine engine = new CareerLearningEngine(successPatterns, careerLearning, true);
        engine.recompute(userId);
        verify(careerLearning, never()).save(argThat(r -> "Backend".equals(r.getDimensionKey())));
    }
}
