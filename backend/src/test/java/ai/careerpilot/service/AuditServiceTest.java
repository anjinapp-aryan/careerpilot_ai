package ai.careerpilot.service;

import ai.careerpilot.domain.AuditLog;
import ai.careerpilot.repo.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 8.1 — verifies AuditService is the first real writer to the previously-dead audit_logs
 * table, is dark-shipped (no-op when disabled), and never propagates a write failure to the caller.
 */
class AuditServiceTest {

    private final AuditLogRepository repo = mock(AuditLogRepository.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private AuditService service(boolean enabled) {
        return new AuditService(repo, enabled);
    }

    @Test
    void disabledNeverWrites() {
        service(false).log(orgId, userId, "a@b.com", "LOGIN_SUCCESS", "User", userId.toString(), null, null);

        verify(repo, never()).save(any());
    }

    @Test
    void enabledWritesActionAndActorFields() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9");
        when(req.getHeader("User-Agent")).thenReturn("test-agent");

        service(true).log(orgId, userId, "a@b.com", "LOGIN_SUCCESS", "User", userId.toString(), req, Map.of("k", "v"));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("LOGIN_SUCCESS");
        assertThat(saved.getActorEmail()).isEqualTo("a@b.com");
        assertThat(saved.getIp()).isEqualTo("9.9.9.9");
        assertThat(saved.getUserAgent()).isEqualTo("test-agent");
        assertThat(saved.getMetadata()).contains("\"k\":\"v\"");
    }

    @Test
    void nullMetadataAndRequestAreHandledSafely() {
        service(true).log(null, null, "unknown@x.com", "LOGIN_FAILED", "User", null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getMetadata()).isEqualTo("{}");
        assertThat(captor.getValue().getIp()).isNull();
    }

    @Test
    void repositoryFailureIsSwallowedNeverThrown() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));

        service(true).log(orgId, userId, "a@b.com", "LOGIN_SUCCESS", "User", userId.toString(), null, null);
        // no exception propagated — success is the assertion
    }
}
