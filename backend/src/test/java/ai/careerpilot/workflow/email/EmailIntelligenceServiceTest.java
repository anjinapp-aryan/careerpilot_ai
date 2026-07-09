package ai.careerpilot.workflow.email;

import ai.careerpilot.domain.ApplicationEmail;
import ai.careerpilot.repo.ApplicationEmailRepository;
import ai.careerpilot.repo.EmailAuditRepository;
import ai.careerpilot.repo.EmailExtractionRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 3A.3 — the email service is INERT (no mailbox). {@code classify} is always available (pure),
 * but {@code process} persists only when the flag is on and never throws.
 */
class EmailIntelligenceServiceTest {

    private final ApplicationEmailRepository emails = mock(ApplicationEmailRepository.class);
    private final EmailExtractionRepository extractions = mock(EmailExtractionRepository.class);
    private final EmailAuditRepository audit = mock(EmailAuditRepository.class);
    private final EmailIntelligenceMetrics metrics = new EmailIntelligenceMetrics();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private EmailIntelligenceService svc(boolean enabled) {
        return new EmailIntelligenceService(emails, extractions, audit, metrics, enabled);
    }

    @Test
    void classifyIsAvailableEvenWhenDisabled() {
        assertThat(svc(false).classify("We are pleased to offer you", "").category())
                .isEqualTo(ApplicationEmail.CATEGORY_OFFER);
    }

    @Test
    void processIsNoOpWhenDisabled() {
        assertThat(svc(false).process(userId, jobId, "offer", "body")).isEmpty();
        verifyNoInteractions(emails);
    }

    @Test
    void processPersistsEmailExtractionAndAudit() {
        when(emails.save(any())).thenAnswer(inv -> {
            ApplicationEmail e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        assertThat(svc(true).process(userId, jobId, "Technical interview", "schedule a call")).isPresent();
        verify(emails).save(any());
        verify(extractions).save(any());
        verify(audit).save(any());
    }

    @Test
    void neverThrowsOnRepoFailure() {
        when(emails.save(any())).thenThrow(new RuntimeException("db down"));
        assertThat(svc(true).process(userId, jobId, "offer", "body")).isEmpty();
    }
}
