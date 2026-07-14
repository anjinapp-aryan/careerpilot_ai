package ai.careerpilot.submission.api;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import ai.careerpilot.submission.ApplicationSubmissionSessionService;
import ai.careerpilot.submission.config.SubmissionExecutorsConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 7.16 — no-auth diagnostics for the submission pipeline, same convention as {@code
 * ExecutionDiagnosticsController}/{@code PipelineDiagnosticsController}: flags, counts, live
 * executor queue depth, and a computed health verdict (NOT_CONFIGURED | UP | DEGRADED | DOWN).
 * Also exposes {@code enabled} for the frontend's dark-flag check ({@code applicationSubmission.ts}).
 */
@RestController
@RequestMapping("/api/diagnostics/application-submission")
public class ApplicationSubmissionDiagnosticsController {

    private final ApplicationSubmissionSessionService service;
    private final ApplicationSubmissionSessionRepository sessions;
    private final ThreadPoolTaskExecutor executor;

    public ApplicationSubmissionDiagnosticsController(
            ApplicationSubmissionSessionService service, ApplicationSubmissionSessionRepository sessions,
            @Qualifier(SubmissionExecutorsConfig.APPLICATION_SUBMISSION_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.service = service;
        this.sessions = sessions;
        this.executor = executor;
    }

    @GetMapping
    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean enabled = service.isEnabled();
        out.put("enabled", enabled);
        out.put("autoEnabled", service.isAutoEnabled());
        out.put("manualEnabled", service.isManualEnabled());
        out.put("approvalEnabled", service.isApprovalEnabled());

        long completed = sessions.countByStatus(ApplicationSubmissionSession.STATUS_COMPLETED);
        long failed = sessions.countByStatus(ApplicationSubmissionSession.STATUS_FAILED);
        long waitingApproval = sessions.countByStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL);
        long submitting = sessions.countByStatus(ApplicationSubmissionSession.STATUS_SUBMITTING);
        long total = sessions.count();

        out.put("sessionsTotal", total);
        out.put("sessionsCompleted", completed);
        out.put("sessionsFailed", failed);
        out.put("sessionsWaitingApproval", waitingApproval);
        out.put("sessionsSubmitting", submitting);

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
        } else if (total > 0 && failed * 100 > total * 30) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        return out;
    }
}
