package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.RecommendationWeight;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdaptiveRecommendationEngineTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void disabledEngineAlwaysReturnsZero() {
        RecommendationWeightManager manager = mock(RecommendationWeightManager.class);
        when(manager.boostFor(any(), any(), any())).thenReturn(25);
        AdaptiveRecommendationEngine engine = new AdaptiveRecommendationEngine(manager, false);

        assertEquals(0, engine.getBoost(userId, RecommendationWeight.DIM_COMPANY, "JP Morgan"));
        assertFalse(engine.isEnabled());
        verifyNoInteractions(manager);
    }

    @Test
    void enabledEngineDelegatesToWeightManager() {
        RecommendationWeightManager manager = mock(RecommendationWeightManager.class);
        when(manager.boostFor(userId, RecommendationWeight.DIM_COMPANY, "JP Morgan")).thenReturn(25);
        AdaptiveRecommendationEngine engine = new AdaptiveRecommendationEngine(manager, true);

        assertEquals(25, engine.getBoost(userId, RecommendationWeight.DIM_COMPANY, "JP Morgan"));
    }

    @Test
    void nullKeyReturnsZeroEvenWhenEnabled() {
        RecommendationWeightManager manager = mock(RecommendationWeightManager.class);
        AdaptiveRecommendationEngine engine = new AdaptiveRecommendationEngine(manager, true);
        assertEquals(0, engine.getBoost(userId, RecommendationWeight.DIM_COMPANY, null));
    }
}
