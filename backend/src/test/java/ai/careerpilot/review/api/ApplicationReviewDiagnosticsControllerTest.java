package ai.careerpilot.review.api;

import ai.careerpilot.repo.ApplicationReviewRepository;
import ai.careerpilot.review.ReviewMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Stock (dark) defaults must report NOT_CONFIGURED — never fabricate an UP status for the review pipeline. */
class ApplicationReviewDiagnosticsControllerTest {

    private ApplicationReviewDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        ApplicationReviewRepository reviews = mock(ApplicationReviewRepository.class);
        when(reviews.count()).thenReturn(0L);
        when(reviews.countByVerdict(any())).thenReturn(0L);
        when(reviews.countByQualityCategory(any())).thenReturn(0L);
        controller = new ApplicationReviewDiagnosticsController(new ReviewMetrics(), reviews, realExecutor());
    }

    private static ThreadPoolTaskExecutor realExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setQueueCapacity(10);
        e.initialize();
        return e;
    }

    @Test
    void reportsNotConfiguredByDefault() {
        Map<String, Object> out = controller.overview();
        assertEquals(false, out.get("enabled"));
        assertEquals("NOT_CONFIGURED", out.get("health"));
        assertEquals(false, out.get("resumeReviewerEnabled"));
        assertEquals(false, out.get("qualityReviewerEnabled"));
        assertEquals(0L, out.get("reviewsCompleted"));
        assertEquals(0L, out.get("verdict.READY"));
        assertEquals(0L, out.get("quality.EXCELLENT"));
    }

    @Test
    void enabledReportsUpWhenQueueHealthy() {
        ReflectionTestUtils.setField(controller, "enabled", true);
        assertEquals("UP", controller.overview().get("health"));
    }
}
