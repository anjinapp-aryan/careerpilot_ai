package ai.careerpilot.packageintel.api;

import ai.careerpilot.packageintel.PackageIntelligenceMetrics;
import ai.careerpilot.packageintel.PackageValidationStatus;
import ai.careerpilot.packageintel.config.PackageIntelligenceExecutorsConfig;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.ApplicationPackageValidationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7.11 — no-auth counts-only diagnostics for the application package intelligence layer, same
 * convention as every other diagnostics controller: enabled flags, package/version/validation counts,
 * generation metrics, live executor queue depth, and a computed health verdict (NOT_CONFIGURED | UP |
 * DEGRADED | DOWN). Served under the permitAll {@code /api/diagnostics/**} space. With stock (dark)
 * flags every stage reports {@code NOT_CONFIGURED}.
 *
 * <p>Path is {@code /package-intelligence} (not {@code /application-package}) because the Phase 2D
 * {@link ai.careerpilot.api.PipelineDiagnosticsController} already owns
 * {@code GET /api/diagnostics/application-package} for the pipeline stage — mapping both fails boot.
 */
@RestController
@RequestMapping("/api/diagnostics/package-intelligence")
public class ApplicationPackageDiagnosticsController {

    private final PackageIntelligenceMetrics metrics;
    private final ApplicationPackageRepository packages;
    private final ApplicationPackageValidationRepository validations;
    private final ThreadPoolTaskExecutor executor;

    @Value("${application.package.enabled:false}") private boolean packageEnabled;
    @Value("${application.package.validation.enabled:false}") private boolean validationEnabled;
    @Value("${application.package.validation.trigger.enabled:false}") private boolean triggerEnabled;
    @Value("${application.package.history.enabled:false}") private boolean historyEnabled;
    @Value("${application.package.diagnostics.enabled:false}") private boolean diagnosticsEnabled;

    public ApplicationPackageDiagnosticsController(PackageIntelligenceMetrics metrics,
                                                   ApplicationPackageRepository packages,
                                                   ApplicationPackageValidationRepository validations,
                                                   @Qualifier(PackageIntelligenceExecutorsConfig.PACKAGE_INTELLIGENCE_EXECUTOR)
                                                   ThreadPoolTaskExecutor executor) {
        this.metrics = metrics;
        this.packages = packages;
        this.validations = validations;
        this.executor = executor;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = stage(validationEnabled);
        out.put("packageEnabled", packageEnabled);
        out.put("validationEnabled", validationEnabled);
        out.put("triggerEnabled", triggerEnabled);
        out.put("historyEnabled", historyEnabled);
        out.put("diagnosticsEnabled", diagnosticsEnabled);
        out.put("packagesGenerated", packages.count());
        out.put("validationRuns", validations.count());
        for (PackageValidationStatus s : PackageValidationStatus.values()) {
            out.put("validation." + s.name(), validations.countByStatus(s.name()));
            out.put("packageStatus." + s.name(), packages.countByValidationStatus(s.name()));
        }
        out.put("averageGenerationMs", metrics.averageGenerationMs());
        out.putAll(metrics.snapshot());
        return out;
    }

    private Map<String, Object> stage(boolean enabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorPoolSize", executor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", executor.getQueueCapacity());
        long failures = metrics.get("failures");
        long generated = metrics.get("generated");

        String health;
        if (!enabled) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= executor.getQueueCapacity()) {
            health = "DOWN";
        } else if (failures > 0 && failures * 100 > Math.max(1, generated) * 30) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        out.put("failureCount", failures);
        return out;
    }
}
