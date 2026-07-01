package ai.careerpilot.service;

import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.RecommendationAudit;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2C-3 — the approval actions must (1) map to the right Application status, (2) upsert rather
 * than duplicate when a row already exists, (3) reject unknown actions, and (4) best-effort stamp
 * the decision onto the latest scoring-audit row for replay. All while reusing the live Application
 * entity — no parallel queue.
 */
class RecommendationDecisionServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private RecommendationDecisionService service(ApplicationRepository apps,
                                                  JobRecommendationRepository recs,
                                                  RecommendationAuditRepository audit) {
        when(apps.save(any(Application.class))).thenAnswer(inv -> inv.getArgument(0));
        return new RecommendationDecisionService(apps, recs, audit, true);
    }

    @Test
    void approveMapsToSavedAndCopiesMatchScoreWhenNoApplicationExists() {
        ApplicationRepository apps = mock(ApplicationRepository.class);
        JobRecommendationRepository recs = mock(JobRecommendationRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);
        when(apps.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());
        when(recs.findByUserIdAndJobId(userId, jobId))
                .thenReturn(Optional.of(JobRecommendation.builder().matchScore(88).build()));
        when(audit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());

        Application result = service(apps, recs, audit).decide(userId, orgId, jobId, "approve", "looks great");

        assertEquals("SAVED", result.getStatus());
        assertEquals(userId, result.getUserId());
        assertEquals(jobId, result.getJobId());
        assertEquals(88, result.getMatchScore());
        assertEquals("looks great", result.getNotes());
    }

    @Test
    void rejectAndArchiveAndSaveMapToTheirStatuses() {
        assertEquals("REJECTED", decideStatus("reject"));
        assertEquals("ARCHIVED", decideStatus("archive"));
        assertEquals("SAVED", decideStatus("save"));
    }

    private String decideStatus(String action) {
        ApplicationRepository apps = mock(ApplicationRepository.class);
        JobRecommendationRepository recs = mock(JobRecommendationRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);
        when(apps.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());
        when(recs.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        when(audit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());
        return service(apps, recs, audit).decide(userId, orgId, jobId, action, null).getStatus();
    }

    @Test
    void existingApplicationIsUpdatedNotDuplicated() {
        ApplicationRepository apps = mock(ApplicationRepository.class);
        JobRecommendationRepository recs = mock(JobRecommendationRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);
        Application existing = Application.builder().userId(userId).orgId(orgId).jobId(jobId).status("SAVED").build();
        when(apps.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(existing));
        when(audit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());

        Application result = service(apps, recs, audit).decide(userId, orgId, jobId, "reject", null);

        assertSame(existing, result);
        assertEquals("REJECTED", result.getStatus());
        verify(recs, never()).findByUserIdAndJobId(any(), any()); // no score lookup when app already exists
    }

    @Test
    void unknownActionThrows() {
        ApplicationRepository apps = mock(ApplicationRepository.class);
        JobRecommendationRepository recs = mock(JobRecommendationRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);
        assertThrows(IllegalArgumentException.class,
                () -> new RecommendationDecisionService(apps, recs, audit, true)
                        .decide(userId, orgId, jobId, "frobnicate", null));
        verifyNoInteractions(apps);
    }

    @Test
    void decisionStampedOntoLatestAuditRowWhenPresent() {
        ApplicationRepository apps = mock(ApplicationRepository.class);
        JobRecommendationRepository recs = mock(JobRecommendationRepository.class);
        RecommendationAuditRepository audit = mock(RecommendationAuditRepository.class);
        RecommendationAudit row = RecommendationAudit.builder().userId(userId).jobId(jobId).build();
        when(apps.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());
        when(recs.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        when(audit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(row));

        service(apps, recs, audit).decide(userId, orgId, jobId, "approve", null);

        ArgumentCaptor<RecommendationAudit> captor = ArgumentCaptor.forClass(RecommendationAudit.class);
        verify(audit).save(captor.capture());
        assertEquals("APPROVE", captor.getValue().getDecision());
    }

    @Test
    void validActionAndEnabledHelpers() {
        assertTrue(RecommendationDecisionService.isValidAction("APPROVE"));
        assertTrue(RecommendationDecisionService.isValidAction("archive"));
        assertFalse(RecommendationDecisionService.isValidAction("frobnicate"));
        assertFalse(RecommendationDecisionService.isValidAction(null));
        assertTrue(new RecommendationDecisionService(mock(ApplicationRepository.class),
                mock(JobRecommendationRepository.class), mock(RecommendationAuditRepository.class), true).isEnabled());
    }
}
