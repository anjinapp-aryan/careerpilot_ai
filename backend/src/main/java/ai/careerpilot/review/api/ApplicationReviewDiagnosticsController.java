package ai.careerpilot.review.api;

import ai.careerpilot.packageintel.PackageValidationStatus;
import ai.careerpilot.review.QualityCategory;
import ai.careerpilot.review.ReviewMetrics;
import ai.careerpilot.review.config.ReviewExecutorsConfig;
import ai.careerpilot.repo.ApplicationReviewRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7.12 — no-auth counts-only diagnostics for the AI Review Pipeline, same convention as every
 * other diagnostics controller: per-reviewer enabled flags, verdict + quality distribution, review
 * metrics (avg time / avg quality / failures), live executor queue depth, and a computed health verdict
 * (NOT_CONFIGURED | UP | DEGRADED | DOWN). Served under the permitAll {@code /api/diagnostics/**} space.
 * With stock (dark) flags every reviewer reports {@code NOT_CONFIGURED}.
 */
@RestController
@RequestMapping("/api/diagnostics/application-review")
public class ApplicationReviewDiagnosticsController {

    private final ReviewMetrics metrics;
    private final ApplicationReviewRepository reviews;
    private final ThreadPoolTaskExecutor executor;

    @Value("${application.review.enabled:false}") private boolean enabled;
    @Value("${application.review.trigger.enabled:false}") private boolean triggerEnabled;
    @Value("${application.review.resume.enabled:false}") private boolean resumeEnabled;
    @Value("${application.review.ats.enabled:false}") private boolean atsEnabled;
    @Value("${application.review.company.enabled:false}") private boolean companyEnabled;
    @Value("${application.review.learning.enabled:false}") private boolean learningEnabled;
    @Value("${application.review.consistency.enabled:false}") private boolean consistencyEnabled;
    @Value("${application.review.quality.enabled:false}") private boolean qualityEnabled;
    @Value("${application.review.diagnostics.enabled:false}") private boolean diagnosticsEnabled;

    public ApplicationReviewDiagnosticsController(ReviewMetrics metrics, ApplicationReviewRepository reviews,
                                                  @Qualifier(ReviewExecutorsConfig.APPLICATION_REVIEW_EXECUTOR)
                                                  ThreadPoolTaskExecutor executor) {
        this.metrics = metrics;
        this.reviews = reviews;
        this.executor = executor;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = stage(enabled);
        out.put("triggerEnabled", triggerEnabled);
        out.put("resumeReviewerEnabled", resumeEnabled);
        out.put("atsReviewerEnabled", atsEnabled);
        out.put("companyReviewerEnabled", companyEnabled);
        out.put("learningReviewerEnabled", learningEnabled);
        out.put("consistencyReviewerEnabled", consistencyEnabled);
        out.put("qualityReviewerEnabled", qualityEnabled);
        out.put("diagnosticsEnabled", diagnosticsEnabled);
        out.put("reviewsCompleted", reviews.count());
        for (PackageValidationStatus v : PackageValidationStatus.values()) {
            out.put("verdict." + v.name(), reviews.countByVerdict(v.name()));
        }
        for (QualityCategory c : QualityCategory.values()) {
            out.put("quality." + c.name(), reviews.countByQualityCategory(c.name()));
        }
        out.put("averageReviewMs", metrics.averageReviewMs());
        out.put("averageQuality", metrics.averageQuality());
        out.putAll(metrics.snapshot());
        return out;
    }

    private Map<String, Object> stage(boolean on) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", on);
        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorPoolSize", executor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", executor.getQueueCapacity());
        long failures = metrics.get("failures");
        long reviewsRun = metrics.get("reviews");

        String health;
        if (!on) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= executor.getQueueCapacity()) {
            health = "DOWN";
        } else if (failures > 0 && failures * 100 > Math.max(1, reviewsRun) * 30) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        out.put("failureCount", failures);
        return out;
    }
}
