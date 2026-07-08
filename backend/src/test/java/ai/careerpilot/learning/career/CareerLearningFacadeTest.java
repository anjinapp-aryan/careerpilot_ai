package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerLearning;
import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerLearningRepository;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareerLearningFacadeTest {

    private CareerStrategyRepository strategies;
    private CareerLearningRepository learning;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        strategies = mock(CareerStrategyRepository.class);
        learning = mock(CareerLearningRepository.class);
    }

    @Test
    void disabledReturnsEmptyAndNeverQueries() {
        CareerLearningFacade facade = new CareerLearningFacade(strategies, learning, false);
        assertFalse(facade.isEnabled());
        assertTrue(facade.strategy(userId).isEmpty());
        assertTrue(facade.top(userId, CareerLearning.DIM_COMPANY).isEmpty());
        verifyNoInteractions(strategies, learning);
    }

    @Test
    void enabledDelegatesToRepositories() {
        CareerStrategy strategy = CareerStrategy.builder().userId(userId).build();
        when(strategies.findByUserId(userId)).thenReturn(Optional.of(strategy));
        CareerLearning row = CareerLearning.builder().userId(userId).dimension(CareerLearning.DIM_COMPANY).build();
        when(learning.findByUserIdAndDimensionOrderByScoreDesc(userId, CareerLearning.DIM_COMPANY))
                .thenReturn(List.of(row));

        CareerLearningFacade facade = new CareerLearningFacade(strategies, learning, true);
        assertEquals(Optional.of(strategy), facade.strategy(userId));
        assertEquals(List.of(row), facade.top(userId, CareerLearning.DIM_COMPANY));
    }
}
