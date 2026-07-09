package ai.careerpilot.autopilot.email;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.workflow.email.EmailClassifier;
import ai.careerpilot.workflow.email.EmailIntelligenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 7.6 — inbound-email ingestion for the autonomous agent. Connects a mailbox (via any external
 * forwarder/webhook the operator wires up) to the <em>existing</em> Phase 3A
 * {@link EmailIntelligenceService} — it does not re-implement classification. The deterministic
 * classifier already fails safe on low confidence (routing to human review), and {@code process}
 * updates the application/timeline/learning through the existing workflow. Gated by
 * {@code email.intelligence.enabled}: a 409 {@code NOT_ENABLED} when off, so nothing is ingested
 * with stock flags. Multi-tenant scoped to the caller.
 */
@RestController
@RequestMapping("/api/autopilot/email")
public class AutopilotEmailController {

    private final EmailIntelligenceService email;

    public AutopilotEmailController(EmailIntelligenceService email) {
        this.email = email;
    }

    @PostMapping("/inbound")
    public ResponseEntity<Map<String, Object>> inbound(AuthenticatedUser user, @RequestBody InboundEmail body) {
        if (!email.isEnabled()) {
            return ResponseEntity.status(409).body(Map.of("status", "NOT_ENABLED"));
        }
        EmailClassifier.Classification c = email.classify(body.subject(), body.body());
        boolean processed = email.process(user.userId(), body.jobId(), body.subject(), body.body()).isPresent();
        return ResponseEntity.ok(Map.of(
                "category", c.category(),
                "confidence", c.confidence(),
                "processed", processed));
    }

    /** Minimal inbound payload: which job the email relates to plus subject/body. */
    public record InboundEmail(UUID jobId, String subject, String body) {}
}
