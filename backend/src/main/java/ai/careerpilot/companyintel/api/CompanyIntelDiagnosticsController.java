package ai.careerpilot.companyintel.api;

import ai.careerpilot.companyintel.CompanyIntelMetrics;
import ai.careerpilot.companyintel.config.CompanyIntelExecutorConfig;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.CompanyKnowledgeVersionRepository;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import ai.careerpilot.repo.CompanyTimelineEventRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7.13 — no-auth counts-only diagnostics for the Company Intelligence engine, same convention
 * as every other diagnostics controller: feature flags, knowledge/graph sizes, update counters,
 * live executor queue depth, and a computed health verdict (NOT_CONFIGURED | UP | DEGRADED | DOWN).
 * Served under the permitAll {@code /api/diagnostics/**} space. With stock (dark) flags it reports
 * {@code NOT_CONFIGURED}.
 */
@RestController
@RequestMapping("/api/diagnostics/company")
public class CompanyIntelDiagnosticsController {

    private final CompanyIntelMetrics metrics;
    private final CompanyKnowledgeRepository companies;
    private final CompanyKnowledgeVersionRepository versions;
    private final CompanyTimelineEventRepository timelineEvents;
    private final CompanyRelationshipRepository relationships;
    private final ThreadPoolTaskExecutor executor;

    @Value("${company.knowledge.enabled:false}") private boolean knowledgeEnabled;
    @Value("${company.graph.enabled:false}") private boolean graphEnabled;
    @Value("${company.similarity.enabled:false}") private boolean similarityEnabled;
    @Value("${company.timeline.enabled:false}") private boolean timelineEnabled;
    @Value("${company.dashboard.enabled:false}") private boolean dashboardEnabled;
    @Value("${company.copilot.enabled:false}") private boolean copilotEnabled;
    @Value("${company.resume.enabled:false}") private boolean resumeEnabled;
    @Value("${company.learning.enabled:false}") private boolean learningEnabled;
    @Value("${company.analytics.enabled:false}") private boolean analyticsEnabled;
    @Value("${company.recommendation.enabled:false}") private boolean recommendationEnabled;

    public CompanyIntelDiagnosticsController(CompanyIntelMetrics metrics,
                                             CompanyKnowledgeRepository companies,
                                             CompanyKnowledgeVersionRepository versions,
                                             CompanyTimelineEventRepository timelineEvents,
                                             CompanyRelationshipRepository relationships,
                                             @Qualifier(CompanyIntelExecutorConfig.COMPANY_INTEL_EXECUTOR)
                                             ThreadPoolTaskExecutor executor) {
        this.metrics = metrics;
        this.companies = companies;
        this.versions = versions;
        this.timelineEvents = timelineEvents;
        this.relationships = relationships;
        this.executor = executor;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", knowledgeEnabled);
        out.put("graphEnabled", graphEnabled);
        out.put("similarityEnabled", similarityEnabled);
        out.put("timelineEnabled", timelineEnabled);
        out.put("dashboardEnabled", dashboardEnabled);
        out.put("copilotEnabled", copilotEnabled);
        out.put("resumeEnabled", resumeEnabled);
        out.put("learningEnabled", learningEnabled);
        out.put("analyticsEnabled", analyticsEnabled);
        out.put("recommendationEnabled", recommendationEnabled);

        out.put("companiesKnown", safeCount(companies::count));
        out.put("knowledgeVersions", safeCount(versions::count));
        out.put("timelineEvents", safeCount(timelineEvents::count));
        out.put("graphEdges", safeCount(relationships::count));

        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorPoolSize", executor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", executor.getQueueCapacity());

        String health;
        if (!knowledgeEnabled) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= executor.getQueueCapacity()) {
            health = "DOWN";
        } else if (metrics.get("ingestFailures") > 0) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        out.putAll(metrics.snapshot());
        return out;
    }

    /** Counts must never take diagnostics down — e.g. before V55 is applied the tables don't exist. */
    private static long safeCount(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            return -1;
        }
    }
}
