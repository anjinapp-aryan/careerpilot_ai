package ai.careerpilot.api;

import ai.careerpilot.applications.ApplicationCardService;
import ai.careerpilot.applications.dto.ApplicationCardDtos.BulkActionRequest;
import ai.careerpilot.applications.dto.ApplicationCardDtos.BulkActionResult;
import ai.careerpilot.domain.Application;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Coverage for the new {@code POST /api/applications/bulk} endpoint: ownership scoping (ids the
 * caller doesn't own are reported as failed, never mutated), each supported action dispatches to
 * the right {@link ApplicationService} method, and an unknown action fails every id without
 * mutating anything.
 */
class ApplicationControllerBulkTest {

    private final ApplicationService apps = mock(ApplicationService.class);
    private final ApplicationCardService cards = mock(ApplicationCardService.class);
    private ApplicationController controller;
    private UUID userId;
    private AuthenticatedUser user;

    private Application appRow(UUID id) {
        return Application.builder().id(id).userId(userId).orgId(UUID.randomUUID()).jobId(UUID.randomUUID())
                .status("SAVED").build();
    }

    @BeforeEach
    void setUp() {
        controller = new ApplicationController(apps, cards);
        userId = UUID.randomUUID();
        user = new AuthenticatedUser(userId, UUID.randomUUID(), "u@example.com", "USER");
    }

    @Test
    void statusActionUpdatesOnlyOwnedIds() {
        UUID owned = UUID.randomUUID();
        UUID notOwned = UUID.randomUUID();
        when(apps.listForUser(userId)).thenReturn(List.of(appRow(owned)));

        BulkActionResult result = controller.bulk(user,
                new BulkActionRequest(List.of(owned, notOwned), "STATUS", Map.of("status", "APPLIED")));

        verify(apps).updateFields(userId, owned, "APPLIED", null, null, null, null);
        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly(notOwned);
    }

    @Test
    void archiveActionSetsArchivedTrue() {
        UUID id = UUID.randomUUID();
        when(apps.listForUser(userId)).thenReturn(List.of(appRow(id)));

        controller.bulk(user, new BulkActionRequest(List.of(id), "ARCHIVE", Map.of()));

        verify(apps).updateFields(userId, id, null, null, null, null, Boolean.TRUE);
    }

    @Test
    void notesActionPassesNotesThrough() {
        UUID id = UUID.randomUUID();
        when(apps.listForUser(userId)).thenReturn(List.of(appRow(id)));

        controller.bulk(user, new BulkActionRequest(List.of(id), "NOTES", Map.of("notes", "hello")));

        verify(apps).updateFields(userId, id, null, "hello", null, null, null);
    }

    @Test
    void resumeActionOnlyReassignsResumeIdNeverTriggersAi() {
        UUID id = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        when(apps.listForUser(userId)).thenReturn(List.of(appRow(id)));

        controller.bulk(user, new BulkActionRequest(List.of(id), "RESUME", Map.of("resumeId", resumeId.toString())));

        verify(apps).reassignResume(userId, id, resumeId);
        verifyNoInteractions(cards);
    }

    @Test
    void unknownActionFailsAllRequestedIdsWithoutMutating() {
        UUID id = UUID.randomUUID();
        when(apps.listForUser(userId)).thenReturn(List.of(appRow(id)));

        BulkActionResult result = controller.bulk(user, new BulkActionRequest(List.of(id), "NOT_A_REAL_ACTION", Map.of()));

        assertThat(result.applied()).isZero();
        assertThat(result.failedIds()).containsExactly(id);
        verify(apps, never()).updateFields(any(), any(), any(), any(), any(), any(), any());
    }
}
