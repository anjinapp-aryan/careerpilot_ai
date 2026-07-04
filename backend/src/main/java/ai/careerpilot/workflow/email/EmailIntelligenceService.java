package ai.careerpilot.workflow.email;

import ai.careerpilot.domain.ApplicationEmail;
import ai.careerpilot.domain.EmailAudit;
import ai.careerpilot.domain.EmailExtraction;
import ai.careerpilot.repo.ApplicationEmailRepository;
import ai.careerpilot.repo.EmailAuditRepository;
import ai.careerpilot.repo.EmailExtractionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3A.3 — email intelligence. INERT by design: there is no mailbox integration and nothing
 * fetches email. Classification/extraction is deterministic ({@link EmailClassifier}); this service
 * only persists a manually-ingested email through the (dark) flag. Flag-gated by
 * {@code email.intelligence.enabled}; append-only; never throws.
 */
@Service
public class EmailIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(EmailIntelligenceService.class);

    private final ApplicationEmailRepository emails;
    private final EmailExtractionRepository extractions;
    private final EmailAuditRepository audit;
    private final EmailIntelligenceMetrics metrics;
    private final boolean enabled;

    public EmailIntelligenceService(ApplicationEmailRepository emails,
                                    EmailExtractionRepository extractions,
                                    EmailAuditRepository audit,
                                    EmailIntelligenceMetrics metrics,
                                    @Value("${email.intelligence.enabled:false}") boolean enabled) {
        this.emails = emails;
        this.extractions = extractions;
        this.audit = audit;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Pure classification, always available for the worker/tests without touching the DB. */
    public EmailClassifier.Classification classify(String subject, String body) {
        return EmailClassifier.classify(subject, body);
    }

    /** Classify + extract + persist a single ingested email. Empty when disabled/failed. Never throws. */
    @Transactional
    public Optional<ApplicationEmail> process(UUID userId, UUID jobId, String subject, String body) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            EmailClassifier.Classification c = EmailClassifier.classify(subject, body);
            EmailClassifier.Extraction ex = EmailClassifier.extract(subject, body);
            ApplicationEmail email = emails.save(ApplicationEmail.builder()
                    .userId(userId).jobId(jobId).subject(subject).body(body)
                    .category(c.category()).confidence(c.confidence())
                    .build());
            extractions.save(EmailExtraction.builder()
                    .emailId(email.getId())
                    .interviewType(ex.interviewType()).salary(ex.salary())
                    .confidence(c.confidence())
                    .build());
            audit.save(EmailAudit.builder()
                    .emailId(email.getId()).action("CLASSIFY")
                    .detail(c.category() + " @ " + c.confidence())
                    .build());
            metrics.recordClassified();
            log.info("EMAIL_INTEL user={} job={} category={}", userId, jobId, c.category());
            return Optional.of(email);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("EMAIL_INTEL error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }
}
