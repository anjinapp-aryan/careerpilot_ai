package ai.careerpilot.autopilot.api;

import ai.careerpilot.autopilot.config.AutopilotExecutorsConfig;
import ai.careerpilot.autopilot.decision.DecisionOutcome;
import ai.careerpilot.autopilot.orchestrator.AutopilotMetrics;
import ai.careerpilot.autopilot.provider.ApplicationProviderRegistry;
import ai.careerpilot.autopilot.provider.SubmissionStatus;
import ai.careerpilot.repo.ApplicationDecisionRepository;
import ai.careerpilot.repo.ApplicationSubmissionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7 — no-auth counts-only diagnostics for the autonomous application agent, same convention as
 * every other diagnostics controller: per-engine enabled flags, decision/submission counts, provider
 * registry, live executor queue depth, and a computed health verdict (NOT_CONFIGURED | UP | DEGRADED
 * | DOWN). With stock (dark) flags every stage reports {@code NOT_CONFIGURED}.
 */
@RestController
@RequestMapping("/api/diagnostics/autopilot")
public class AutopilotDiagnosticsController {

    private final AutopilotMetrics metrics;
    private final ApplicationDecisionRepository decisions;
    private final ApplicationSubmissionRepository submissions;
    private final ApplicationProviderRegistry providers;
    private final ThreadPoolTaskExecutor autopilotExecutor;

    @Value("${career.orchestrator.enabled:false}") private boolean orchestratorEnabled;
    @Value("${application.decision.enabled:false}") private boolean decisionEnabled;
    @Value("${resume.selection.enabled:false}") private boolean resumeSelectionEnabled;
    @Value("${resume.autopilot.enabled:false}") private boolean resumeAutopilotEnabled;
    @Value("${application.auto.enabled:false}") private boolean autoApplyEnabled;
    @Value("${company.research.enabled:false}") private boolean companyResearchEnabled;
    @Value("${interview.prep.enabled:false}") private boolean interviewPrepEnabled;
    @Value("${calendar.integration.enabled:false}") private boolean calendarEnabled;

    public AutopilotDiagnosticsController(AutopilotMetrics metrics, ApplicationDecisionRepository decisions,
                                          ApplicationSubmissionRepository submissions, ApplicationProviderRegistry providers,
                                          @Qualifier(AutopilotExecutorsConfig.AUTOPILOT_EXECUTOR) ThreadPoolTaskExecutor autopilotExecutor) {
        this.metrics = metrics;
        this.decisions = decisions;
        this.submissions = submissions;
        this.providers = providers;
        this.autopilotExecutor = autopilotExecutor;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = stage(orchestratorEnabled);
        out.put("decisionEnabled", decisionEnabled);
        out.put("resumeSelectionEnabled", resumeSelectionEnabled);
        out.put("resumeAutopilotEnabled", resumeAutopilotEnabled);
        out.put("autoApplyEnabled", autoApplyEnabled);
        out.put("companyResearchEnabled", companyResearchEnabled);
        out.put("interviewPrepEnabled", interviewPrepEnabled);
        out.put("calendarEnabled", calendarEnabled);
        out.putAll(metrics.snapshot());
        out.put("providers", providers.providerNames());
        return out;
    }

    @GetMapping("/decision")
    public Map<String, Object> decision() {
        Map<String, Object> out = stage(decisionEnabled);
        out.put("totalDecisions", decisions.count());
        for (DecisionOutcome o : DecisionOutcome.values()) {
            out.put("outcome." + o.name(), decisions.countByOutcome(o.name()));
        }
        return out;
    }

    @GetMapping("/apply")
    public Map<String, Object> apply() {
        Map<String, Object> out = stage(autoApplyEnabled);
        out.put("totalSubmissions", submissions.count());
        for (SubmissionStatus s : SubmissionStatus.values()) {
            out.put("status." + s.name(), submissions.countByStatus(s.name()));
        }
        out.put("providers", providers.providerNames());
        return out;
    }

    private Map<String, Object> stage(boolean enabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        int queueSize = autopilotExecutor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", autopilotExecutor.getActiveCount());
        out.put("executorPoolSize", autopilotExecutor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", autopilotExecutor.getQueueCapacity());
        long failures = metrics.get("failures");

        String health;
        if (!enabled) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= autopilotExecutor.getQueueCapacity()) {
            health = "DOWN";
        } else if (failures > 0 && failures * 100 > Math.max(1, metrics.get("jobsProcessed")) * 30) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        return out;
    }
}
