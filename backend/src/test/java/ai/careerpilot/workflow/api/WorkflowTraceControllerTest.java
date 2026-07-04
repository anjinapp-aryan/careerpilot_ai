package ai.careerpilot.workflow.api;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.CorrelationDiagnosticsDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowSummaryDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowTraceDto;
import ai.careerpilot.workflow.trace.WorkflowTraceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Phase 3A follow-up — the trace controller is a thin, tenant-scoped delegate: it must pass the
 * authenticated caller's {@code userId} into the service (never trust a client-supplied id) and let the
 * service's not-found propagate to the shared handler (→ 404).
 */
class WorkflowTraceControllerTest {

    private final WorkflowTraceService service = mock(WorkflowTraceService.class);
    private final WorkflowTraceController controller = new WorkflowTraceController(service);

    private final UUID userId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, UUID.randomUUID(), "a@b.co", "USER");
    private final String cid = UUID.randomUUID().toString();

    @Test
    void correlationDelegatesWithAuthenticatedUserId() {
        WorkflowTraceDto dto = new WorkflowTraceDto(cid, "APPLICATION_TRACKING", "RUNNING",
                null, null, null, List.of(), List.of());
        when(service.getWorkflow(cid, userId)).thenReturn(dto);
        assertThat(controller.correlation(user, cid)).isSameAs(dto);
        verify(service).getWorkflow(cid, userId);
    }

    @Test
    void summaryDelegatesWithAuthenticatedUserId() {
        WorkflowSummaryDto dto = new WorkflowSummaryDto(cid, "APPLICATION_TRACKING", "RUNNING", 10, 4, 0, 0, null);
        when(service.summarize(cid, userId)).thenReturn(dto);
        assertThat(controller.summary(user, cid)).isSameAs(dto);
        verify(service).summarize(cid, userId);
    }

    @Test
    void diagnosticsDelegatesWithAuthenticatedUserId() {
        CorrelationDiagnosticsDto dto = new CorrelationDiagnosticsDto(cid, "APPLICATION_TRACKING", "RUNNING", 4, 4, 0, 0, null);
        when(service.diagnostics(cid, userId)).thenReturn(dto);
        assertThat(controller.diagnostics(user, cid)).isSameAs(dto);
        verify(service).diagnostics(cid, userId);
    }

    @Test
    void notFoundPropagatesToHandler() {
        when(service.getWorkflow(cid, userId)).thenThrow(new NoSuchElementException("not found"));
        assertThatThrownBy(() -> controller.correlation(user, cid)).isInstanceOf(NoSuchElementException.class);
    }
}
