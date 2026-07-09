package ai.careerpilot.learning.api;

import ai.careerpilot.learning.LearningMetrics;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.RecommendationWeightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Stock (dark) defaults must report NOT_CONFIGURED everywhere — never fabricate an UP status. */
class LearningDiagnosticsControllerTest {

    private LearningDiagnosticsController controller;
    private LearningEventRepository events;
    private RecommendationWeightRepository weights;

    @BeforeEach
    void setUp() {
        events = mock(LearningEventRepository.class);
        weights = mock(RecommendationWeightRepository.class);
        when(events.count()).thenReturn(0L);
        when(weights.count()).thenReturn(0L);

        ThreadPoolTaskExecutor learningExecutor = realExecutor();
        ThreadPoolTaskExecutor successExecutor = realExecutor();
        ThreadPoolTaskExecutor failureExecutor = realExecutor();
        ThreadPoolTaskExecutor resumeExecutor = realExecutor();
        ThreadPoolTaskExecutor careerExecutor = realExecutor();

        controller = new LearningDiagnosticsController(new LearningMetrics(), events, weights,
                learningExecutor, successExecutor, failureExecutor, resumeExecutor, careerExecutor);
    }

    private static ThreadPoolTaskExecutor realExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
        return executor;
    }

    @Test
    void overviewReportsNotConfiguredByDefault() {
        Map<String, Object> out = controller.overview();
        assertEquals(false, out.get("enabled"));
        assertEquals("NOT_CONFIGURED", out.get("health"));
        assertEquals(0L, out.get("totalLearningEvents"));
        assertEquals(0L, out.get("totalRecommendationWeights"));
    }

    @Test
    void successReportsNotConfiguredByDefault() {
        assertEquals("NOT_CONFIGURED", controller.success().get("health"));
    }

    @Test
    void failureReportsNotConfiguredByDefault() {
        assertEquals("NOT_CONFIGURED", controller.failure().get("health"));
    }

    @Test
    void resumeReportsNotConfiguredByDefault() {
        assertEquals("NOT_CONFIGURED", controller.resume().get("health"));
    }

    @Test
    void careerReportsNotConfiguredByDefault() {
        assertEquals("NOT_CONFIGURED", controller.career().get("health"));
    }
}
