package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerLearning;
import ai.careerpilot.repo.CareerLearningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerTrajectoryAnalyzerTest {

    private CareerLearningRepository careerLearning;
    private CareerTrajectoryAnalyzer analyzer;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        careerLearning = mock(CareerLearningRepository.class);
        analyzer = new CareerTrajectoryAnalyzer(careerLearning);
        when(careerLearning.findByUserIdAndDimensionOrderByScoreDesc(any(), any())).thenReturn(List.of());
    }

    private CareerLearning row(String dimension, String key) {
        return CareerLearning.builder().userId(userId).dimension(dimension).dimensionKey(key).build();
    }

    @Test
    void noDataProducesNotEnoughHistoryMessage() {
        String result = analyzer.recommend(userId);
        assertEquals("Not enough learned history yet to recommend a trajectory.", result);
    }

    @Test
    void mentionsTopCompanyWhenPresent() {
        when(careerLearning.findByUserIdAndDimensionOrderByScoreDesc(userId, CareerLearning.DIM_COMPANY))
                .thenReturn(List.of(row(CareerLearning.DIM_COMPANY, "JP Morgan")));
        String result = analyzer.recommend(userId);
        assertTrue(result.contains("JP Morgan"));
    }

    @Test
    void combinesMultipleDimensionsWhenAllPresent() {
        when(careerLearning.findByUserIdAndDimensionOrderByScoreDesc(userId, CareerLearning.DIM_COMPANY))
                .thenReturn(List.of(row(CareerLearning.DIM_COMPANY, "JP Morgan")));
        when(careerLearning.findByUserIdAndDimensionOrderByScoreDesc(userId, CareerLearning.DIM_SKILL))
                .thenReturn(List.of(row(CareerLearning.DIM_SKILL, "Java")));
        when(careerLearning.findByUserIdAndDimensionOrderByScoreDesc(userId, CareerLearning.DIM_INDUSTRY))
                .thenReturn(List.of(row(CareerLearning.DIM_INDUSTRY, "Finance")));

        String result = analyzer.recommend(userId);
        assertTrue(result.contains("JP Morgan"));
        assertTrue(result.contains("Java"));
        assertTrue(result.contains("Finance"));
    }
}
