package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.repo.RecommendationWeightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationWeightManagerTest {

    private RecommendationWeightRepository weights;
    private RecommendationWeightManager manager;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        weights = mock(RecommendationWeightRepository.class);
        manager = new RecommendationWeightManager(weights);
        when(weights.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void upsertCreatesNewRowWhenNoneExists() {
        when(weights.findByUserIdAndDimensionAndDimensionKey(userId, RecommendationWeight.DIM_COMPANY, "JP Morgan"))
                .thenReturn(Optional.empty());
        manager.upsert(userId, RecommendationWeight.DIM_COMPANY, "JP Morgan", 25, "learned");

        var captor = org.mockito.ArgumentCaptor.forClass(RecommendationWeight.class);
        verify(weights).save(captor.capture());
        assertEquals(25, captor.getValue().getBoost());
        assertEquals("JP Morgan", captor.getValue().getDimensionKey());
    }

    @Test
    void upsertUpdatesExistingRowInPlace() {
        RecommendationWeight existing = RecommendationWeight.builder().userId(userId)
                .dimension(RecommendationWeight.DIM_LOCATION).dimensionKey("Germany").boost(0).build();
        when(weights.findByUserIdAndDimensionAndDimensionKey(userId, RecommendationWeight.DIM_LOCATION, "Germany"))
                .thenReturn(Optional.of(existing));

        manager.upsert(userId, RecommendationWeight.DIM_LOCATION, "Germany", -20, "no responses");

        verify(weights).save(existing);
        assertEquals(-20, existing.getBoost());
    }

    @Test
    void boostForReturnsZeroWhenUnknown() {
        when(weights.findByUserIdAndDimensionAndDimensionKey(any(), any(), any())).thenReturn(Optional.empty());
        assertEquals(0, manager.boostFor(userId, RecommendationWeight.DIM_COMPANY, "Unknown"));
    }

    @Test
    void findByUserDelegatesToRepository() {
        when(weights.findByUserId(userId)).thenReturn(List.of());
        assertTrue(manager.findByUser(userId).isEmpty());
        verify(weights).findByUserId(userId);
    }
}
