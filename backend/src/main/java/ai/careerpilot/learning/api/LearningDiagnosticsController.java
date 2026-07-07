package ai.careerpilot.learning.api;

import ai.careerpilot.domain.LearningMetricsLog;
import ai.careerpilot.learning.LearningMetrics;
import ai.careerpilot.learning.config.LearningExecutorsConfig;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.RecommendationWeightRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 6 — no-auth counts-only diagnostics for the learning engine, same convention as every
 * other diagnostics controller in this codebase: enabled flags, throughput/latency counters, live
 * executor queue depth, and a computed health verdict (NOT_CONFIGURED | UP | DEGRADED | DOWN).
 */
@RestController
@RequestMapping("/api/diagnostics/learning")
public class LearningDiagnosticsController {

    private final LearningMetrics metrics;
    private final LearningEventRepository events;
    private final RecommendationWeightRepository weights;

    private final ThreadPoolTaskExecutor learningExecutor;
    private final ThreadPoolTaskExecutor successPatternExecutor;
    private final ThreadPoolTaskExecutor failurePatternExecutor;
    private final ThreadPoolTaskExecutor resumeLearningExecutor;
    private final ThreadPoolTaskExecutor careerLearningExecutor;

    @Value("${learning.engine.enabled:false}") private boolean learningEnabled;
    @Value("${learning.success-pattern.enabled:false}") private boolean successPatternEnabled;
    @Value("${learning.failure-pattern.enabled:false}") private boolean failurePatternEnabled;
    @Value("${learning.adaptive-recommendation.enabled:false}") private boolean adaptiveRecommendationEnabled;
    @Value("${learning.adaptive-resume.enabled:false}") private boolean adaptiveResumeEnabled;
    @Value("${learning.adaptive-career.enabled:false}") private boolean adaptiveCareerEnabled;

    public LearningDiagnosticsController(
            LearningMetrics metrics, LearningEventRepository events, RecommendationWeightRepository weights,
            @Qualifier(LearningExecutorsConfig.LEARNING_EXECUTOR) ThreadPoolTaskExecutor learningExecutor,
            @Qualifier(LearningExecutorsConfig.SUCCESS_PATTERN_EXECUTOR) ThreadPoolTaskExecutor successPatternExecutor,
            @Qualifier(LearningExecutorsConfig.FAILURE_PATTERN_EXECUTOR) ThreadPoolTaskExecutor failurePatternExecutor,
            @Qualifier(LearningExecutorsConfig.RESUME_LEARNING_EXECUTOR) ThreadPoolTaskExecutor resumeLearningExecutor,
            @Qualifier(LearningExecutorsConfig.CAREER_LEARNING_EXECUTOR) ThreadPoolTaskExecutor careerLearningExecutor) {
        this.metrics = metrics;
        this.events = events;
        this.weights = weights;
        this.learningExecutor = learningExecutor;
        this.successPatternExecutor = successPatternExecutor;
        this.failurePatternExecutor = failurePatternExecutor;
        this.resumeLearningExecutor = resumeLearningExecutor;
        this.careerLearningExecutor = careerLearningExecutor;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = stage(learningEnabled, learningEnabled,
                metrics.snapshot(LearningMetricsLog.STAGE_EVENT_CAPTURE), learningExecutor,
                metrics.total(LearningMetricsLog.STAGE_EVENT_CAPTURE), metrics.failures(LearningMetricsLog.STAGE_EVENT_CAPTURE));
        out.put("totalLearningEvents", events.count());
        out.put("adaptiveRecommendationEnabled", adaptiveRecommendationEnabled);
        out.putAll(metrics.snapshot(LearningMetricsLog.STAGE_RECOMMENDATION_LEARNING));
        out.put("totalRecommendationWeights", weights.count());
        return out;
    }

    @GetMapping("/success")
    public Map<String, Object> success() {
        return stage(successPatternEnabled, successPatternEnabled,
                metrics.snapshot(LearningMetricsLog.STAGE_SUCCESS_PATTERN), successPatternExecutor,
                metrics.total(LearningMetricsLog.STAGE_SUCCESS_PATTERN), metrics.failures(LearningMetricsLog.STAGE_SUCCESS_PATTERN));
    }

    @GetMapping("/failure")
    public Map<String, Object> failure() {
        return stage(failurePatternEnabled, failurePatternEnabled,
                metrics.snapshot(LearningMetricsLog.STAGE_FAILURE_PATTERN), failurePatternExecutor,
                metrics.total(LearningMetricsLog.STAGE_FAILURE_PATTERN), metrics.failures(LearningMetricsLog.STAGE_FAILURE_PATTERN));
    }

    @GetMapping("/resume")
    public Map<String, Object> resume() {
        return stage(adaptiveResumeEnabled, adaptiveResumeEnabled,
                metrics.snapshot(LearningMetricsLog.STAGE_RESUME_LEARNING), resumeLearningExecutor,
                metrics.total(LearningMetricsLog.STAGE_RESUME_LEARNING), metrics.failures(LearningMetricsLog.STAGE_RESUME_LEARNING));
    }

    @GetMapping("/career")
    public Map<String, Object> career() {
        return stage(adaptiveCareerEnabled, adaptiveCareerEnabled,
                metrics.snapshot(LearningMetricsLog.STAGE_CAREER_LEARNING), careerLearningExecutor,
                metrics.total(LearningMetricsLog.STAGE_CAREER_LEARNING), metrics.failures(LearningMetricsLog.STAGE_CAREER_LEARNING));
    }

    private static Map<String, Object> stage(boolean enabled, boolean triggerEnabled,
                                             Map<String, Object> stageMetrics, ThreadPoolTaskExecutor executor,
                                             Long total, Long failures) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("triggerEnabled", triggerEnabled);
        out.putAll(stageMetrics);
        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorPoolSize", executor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", executor.getQueueCapacity());

        String health;
        if (!enabled) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= executor.getQueueCapacity()) {
            health = "DOWN";
        } else if (total != null && total > 0 && failures != null && failures * 100 > total * 30) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        return out;
    }
}
