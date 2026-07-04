package ai.careerpilot.workflow.api;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.CorrelationDiagnosticsDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowEventDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowGraphDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowSummaryDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowTraceDto;
import ai.careerpilot.workflow.trace.WorkflowTraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 3A follow-up — the read-only Workflow Correlation Trace API. Reconstructs a whole workflow
 * journey for a {@code correlationId} from the existing Phase 3A tables (no new persistence). Unlike the
 * counts-only public {@code /api/diagnostics/*} surface, these endpoints return per-workflow identifiers,
 * so they are JWT-authenticated and multi-tenant-scoped: the {@link WorkflowTraceService} reports a
 * correlation owned by another user as 404 (never 403) so existence cannot be probed.
 *
 * <p>Error mapping is via the shared {@code GlobalExceptionHandler}: a missing/cross-tenant correlation
 * → 404 (NoSuchElementException), a malformed id → 400 (IllegalArgumentException).
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowTraceController {

    private final WorkflowTraceService trace;

    public WorkflowTraceController(WorkflowTraceService trace) {
        this.trace = trace;
    }

    /** Full reconstructed workflow journey for a correlation id. */
    @GetMapping("/correlation/{correlationId}")
    public WorkflowTraceDto correlation(AuthenticatedUser user, @PathVariable String correlationId) {
        return trace.getWorkflow(correlationId, user.userId());
    }

    /** Lightweight roll-up (status + step/dead-letter counts + duration) for list/badge rendering. */
    @GetMapping("/correlation/{correlationId}/summary")
    public WorkflowSummaryDto summary(AuthenticatedUser user, @PathVariable String correlationId) {
        return trace.summarize(correlationId, user.userId());
    }

    /** Counts-oriented diagnostics view for the same correlation (tenant-scoped, unlike the public ones). */
    @GetMapping("/diagnostics/correlation/{correlationId}")
    public CorrelationDiagnosticsDto diagnostics(AuthenticatedUser user, @PathVariable String correlationId) {
        return trace.diagnostics(correlationId, user.userId());
    }

    /** Graph projection ({@code nodes[] / edges[]}) of the reconstructed trace, for workflow visualization. */
    @GetMapping("/correlation/{correlationId}/graph")
    public WorkflowGraphDto graph(AuthenticatedUser user, @PathVariable String correlationId) {
        return trace.graph(correlationId, user.userId());
    }

    /** Ordered raw-event projection for support/debugging (one event per stage transition that occurred). */
    @GetMapping("/correlation/{correlationId}/events")
    public List<WorkflowEventDto> events(AuthenticatedUser user, @PathVariable String correlationId) {
        return trace.events(correlationId, user.userId());
    }
}
