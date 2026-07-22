package ai.careerpilot.service;

import ai.careerpilot.domain.AuditLog;
import ai.careerpilot.repo.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 8.1 — the {@code audit_logs} table and repository have existed since {@code V1__init.sql}
 * with zero writers (confirmed by architecture review). This is the first writer: security-relevant
 * events only (login/register success and failure) rather than a general-purpose audit-everything
 * facility, which would be a much larger surface than this hardening pass calls for. Follows the
 * same non-throwing, dark-shipped convention as every other side-effect service in this codebase —
 * a write failure is logged and swallowed, never propagated to the caller.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(AuditLogRepository repo, @Value("${security.audit.enabled:false}") boolean enabled) {
        this.repo = repo;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void log(UUID orgId, UUID userId, String actorEmail, String action, String targetType, String targetId,
                    HttpServletRequest request, Map<String, Object> metadata) {
        if (!enabled) return;
        try {
            repo.save(AuditLog.builder()
                    .orgId(orgId)
                    .userId(userId)
                    .actorEmail(actorEmail)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .ip(request != null ? clientIp(request) : null)
                    .userAgent(request != null ? request.getHeader("User-Agent") : null)
                    .metadata(mapper.writeValueAsString(metadata == null ? Map.of() : metadata))
                    .build());
        } catch (Exception e) {
            log.warn("Audit log write failed action={}: {}", action, e.toString());
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
