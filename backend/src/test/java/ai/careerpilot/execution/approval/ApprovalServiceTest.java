package ai.careerpilot.execution.approval;

import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import ai.careerpilot.repo.ApprovalAuditRepository;
import ai.careerpilot.repo.ApprovalQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2E.4 — the approval gate is the only thing standing between a validated package and
 * execution. Tests cover: enqueue is flag-gated; approve is the SOLE publisher of
 * ApprovalGrantedEvent; reject publishes nothing; and the PENDING-only double-guard rejects any
 * attempt to re-decide a terminal row (409) or decide another user's row (403).
 */
class ApprovalServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID pkgId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();

    private ApprovalQueueRepository queue;
    private ApprovalAuditRepository audit;
    private ApplicationEventPublisher events;

    @BeforeEach
    void setUp() {
        queue = mock(ApprovalQueueRepository.class);
        audit = mock(ApprovalAuditRepository.class);
        events = mock(ApplicationEventPublisher.class);
        when(queue.save(any(ApprovalQueueEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * P7 Action 2 — approve()/reject() now claim via {@link ApprovalQueueRepository#claimDecision},
     * an atomic conditional UPDATE (see the real-Postgres concurrency proof in
     * ApprovalServiceConcurrencyTest), not a read-then-save. A winning claim is simulated here by
     * stubbing claimDecision to return 1 and re-stubbing findById to return the post-claim row —
     * the same two-step shape the real repository method + a follow-up findById produce.
     */
    private void stubWinningClaim(ApprovalQueueEntry preClaim, String newStatus) {
        when(queue.findById(approvalId)).thenReturn(Optional.of(preClaim));
        when(queue.claimDecision(eq(approvalId), eq(newStatus), any(), any(), any()))
                .thenAnswer(inv -> {
                    // Mutated in place rather than via toBuilder (not enabled on this Lombok
                    // @Builder) — fine here since each test constructs its own fresh entity.
                    preClaim.setStatus(newStatus);
                    when(queue.findById(approvalId)).thenReturn(Optional.of(preClaim));
                    return 1;
                });
    }

    /** A losing/racing claim: the row is already in {@code currentStatus}, claimDecision always refuses. */
    private void stubLosingClaim(ApprovalQueueEntry current) {
        when(queue.findById(approvalId)).thenReturn(Optional.of(current));
        when(queue.claimDecision(eq(approvalId), any(), any(), any(), any())).thenReturn(0);
    }

    private ApprovalService service(boolean enabled) {
        return new ApprovalService(queue, audit, new ApprovalMetrics(), events, enabled);
    }

    private ApprovalQueueEntry pending() {
        return ApprovalQueueEntry.builder()
                .id(approvalId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .safetyVerdict("SAFE").status(ApprovalQueueEntry.STATUS_PENDING).build();
    }

    // ── enqueue ──

    @Test
    void enqueueIsANoOpWhenDisabled() {
        assertThat(service(false).enqueue(userId, jobId, pkgId, "SAFE")).isEmpty();
        verifyNoInteractions(queue);
    }

    @Test
    void enqueueParksAPendingRow() {
        Optional<ApprovalQueueEntry> r = service(true).enqueue(userId, jobId, pkgId, "REVIEW");
        assertThat(r).isPresent();
        assertThat(r.get().getStatus()).isEqualTo(ApprovalQueueEntry.STATUS_PENDING);
        verify(events, never()).publishEvent(any()); // enqueue must NOT advance the pipeline
    }

    // ── approve: sole publisher of ApprovalGrantedEvent ──

    @Test
    void approveTransitionsToApprovedAndPublishesEvent() {
        stubWinningClaim(pending(), ApprovalQueueEntry.STATUS_APPROVED);
        ApprovalQueueEntry r = service(true).approve(approvalId, userId, "boss@x.com", "looks good");
        assertThat(r.getStatus()).isEqualTo(ApprovalQueueEntry.STATUS_APPROVED);
        verify(events).publishEvent(any(ApprovalGrantedEvent.class));
    }

    @Test
    void approveOnAlreadyApprovedRowConflicts() {
        ApprovalQueueEntry approved = pending();
        approved.setStatus(ApprovalQueueEntry.STATUS_APPROVED);
        stubLosingClaim(approved);
        assertThatThrownBy(() -> service(true).approve(approvalId, userId, "boss@x.com", null))
                .isInstanceOf(IllegalStateException.class);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void approveOnRejectedRowConflicts() {
        ApprovalQueueEntry rejected = pending();
        rejected.setStatus(ApprovalQueueEntry.STATUS_REJECTED);
        stubLosingClaim(rejected);
        assertThatThrownBy(() -> service(true).approve(approvalId, userId, "boss@x.com", null))
                .isInstanceOf(IllegalStateException.class);
    }

    /** P7 Action 2 — the race this action closes: claimDecision refuses even though the row IS PENDING. */
    @Test
    void approveConflictsWhenAConcurrentCallerAlreadyWonTheClaim() {
        when(queue.findById(approvalId)).thenReturn(Optional.of(pending()));
        when(queue.claimDecision(eq(approvalId), eq(ApprovalQueueEntry.STATUS_APPROVED), any(), any(), any()))
                .thenReturn(0);
        assertThatThrownBy(() -> service(true).approve(approvalId, userId, "boss@x.com", null))
                .isInstanceOf(IllegalStateException.class);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void approveOnAnotherUsersRowIsForbidden() {
        when(queue.findById(approvalId)).thenReturn(Optional.of(pending()));
        assertThatThrownBy(() -> service(true).approve(approvalId, UUID.randomUUID(), "hacker@x.com", null))
                .isInstanceOf(SecurityException.class);
        verify(queue, never()).claimDecision(any(), any(), any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void approveOnMissingRowIsNotFound() {
        when(queue.findById(approvalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true).approve(approvalId, userId, "boss@x.com", null))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ── reject: publishes nothing ──

    @Test
    void rejectTransitionsToRejectedAndPublishesNothing() {
        stubWinningClaim(pending(), ApprovalQueueEntry.STATUS_REJECTED);
        ApprovalQueueEntry r = service(true).reject(approvalId, userId, "boss@x.com", "not a fit");
        assertThat(r.getStatus()).isEqualTo(ApprovalQueueEntry.STATUS_REJECTED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void rejectOnTerminalRowConflicts() {
        ApprovalQueueEntry approved = pending();
        approved.setStatus(ApprovalQueueEntry.STATUS_APPROVED);
        stubLosingClaim(approved);
        assertThatThrownBy(() -> service(true).reject(approvalId, userId, "boss@x.com", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pendingListDelegatesToRepo() {
        service(true).pending(userId);
        verify(queue).findByUserIdAndStatusOrderByRequestedAtDesc(userId, ApprovalQueueEntry.STATUS_PENDING);
    }
}
