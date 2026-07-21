package ai.careerpilot.memory;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.RecommendationFeedback;
import ai.careerpilot.repo.CareerDecisionMemoryRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 7.15.1 — record() must be a safe no-op when disabled (so every caller, including event
 * listeners, can call it unconditionally); relevantFor() must rank by confidence x importance x
 * freshness and truncate to the requested limit, never dump the full history.
 */
class CareerMemoryServiceTest {

    private final UUID userId = UUID.randomUUID();

    private CareerMemoryService service(CareerDecisionMemoryRepository repo, JobRepository jobs, boolean enabled) {
        return new CareerMemoryService(repo, jobs, new CareerMemoryMetrics(), enabled);
    }

    @Test
    void disabledRecordIsANoOpAndNeverWrites() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        service(repo, jobs, false).record(userId, "JOB_REJECTED", "APPLICATION", "Amazon", "relocation",
                BigDecimal.ONE, "TEST", 5, false, null, null, null, null);
        verify(repo, never()).save(any());
    }

    @Test
    void enabledRecordPersistsWithClampedImportance() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        when(repo.save(any(CareerDecisionMemory.class))).thenAnswer(inv -> inv.getArgument(0));

        service(repo, jobs, true).record(userId, "JOB_REJECTED", "APPLICATION", "Amazon", "relocation required",
                BigDecimal.ONE, "TEST", 99, false, null, null, null, null); // 99 must clamp to 5

        ArgumentCaptor<CareerDecisionMemory> captor = ArgumentCaptor.forClass(CareerDecisionMemory.class);
        verify(repo).save(captor.capture());
        assertEquals((short) 5, captor.getValue().getImportance());
        assertEquals("relocation required", captor.getValue().getReason());
        assertEquals(userId, captor.getValue().getUserId());
    }

    @Test
    void recordNeverThrowsEvenOnRepositoryFailure() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service(repo, jobs, true).record(userId, "X", "CAREER", null, null,
                BigDecimal.ONE, "TEST", 3, true, null, null, null, null));
    }

    @Test
    void relevantForIsEmptyWhenDisabledRegardlessOfData() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        assertTrue(service(repo, jobs, false).relevantFor(userId, "CAREER", 5).isEmpty());
        verifyNoInteractions(repo);
    }

    @Test
    void relevantForRanksByConfidenceImportanceAndFreshnessAndTruncates() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        JobRepository jobs = mock(JobRepository.class);

        CareerDecisionMemory strongRecent = memory("STRONG_RECENT", 5, BigDecimal.ONE, 1);
        CareerDecisionMemory weakOld = memory("WEAK_OLD", 1, BigDecimal.valueOf(0.3), 400);
        CareerDecisionMemory mediumMidAge = memory("MEDIUM", 3, BigDecimal.valueOf(0.8), 30);

        when(repo.findActiveByUserIdAndCategory(eq(userId), eq("CAREER"), any(Instant.class)))
                .thenReturn(List.of(weakOld, strongRecent, mediumMidAge)); // deliberately unordered input

        List<CareerDecisionMemory> result = service(repo, jobs, true).relevantFor(userId, "CAREER", 2);

        assertEquals(2, result.size(), "must truncate to the requested limit, never dump everything");
        assertEquals("STRONG_RECENT", result.get(0).getDecisionType(), "highest confidence+importance+freshness must rank first");
        assertEquals("MEDIUM", result.get(1).getDecisionType());
        assertFalse(result.contains(weakOld), "weakest/oldest memory must be dropped by the limit");
    }

    @Test
    void captureFeedbackDescribesTheJobAndMapsActionToDecisionType() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        when(repo.save(any(CareerDecisionMemory.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setTitle("Senior Backend Engineer");
        job.setCompany("Amazon");
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));

        RecommendationFeedback feedback = RecommendationFeedback.builder()
                .userId(userId).jobId(jobId).action("REJECT").reason("relocation required").build();

        service(repo, jobs, true).captureFeedback(feedback);

        ArgumentCaptor<CareerDecisionMemory> captor = ArgumentCaptor.forClass(CareerDecisionMemory.class);
        verify(repo).save(captor.capture());
        assertEquals("JOB_REJECTED", captor.getValue().getDecisionType());
        assertEquals("Senior Backend Engineer @ Amazon", captor.getValue().getValue());
        assertEquals("relocation required", captor.getValue().getReason());
        assertEquals((short) 5, captor.getValue().getImportance(), "a typed reason must count as high importance");
    }

    @Test
    void captureFeedbackIsANoOpWhenDisabled() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        RecommendationFeedback feedback = RecommendationFeedback.builder()
                .userId(userId).jobId(UUID.randomUUID()).action("REJECT").reason("x").build();
        service(repo, jobs, false).captureFeedback(feedback);
        verify(repo, never()).save(any());
        verifyNoInteractions(jobs);
    }

    @Test
    void summaryForIsEmptyWhenDisabled() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        var summary = service(repo, mock(JobRepository.class), false).summaryFor(userId);
        assertEquals(0, summary.totalMemories());
        assertEquals(0, summary.verifiedCount());
        verifyNoInteractions(repo);
    }

    @Test
    void summaryForCountsVerifiedNeedsReviewAndLowConfidence() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        CareerDecisionMemory verified = memory("A_POSITIVE", 5, BigDecimal.valueOf(0.95), 1);
        verified.setUserConfirmed(true);
        CareerDecisionMemory needsReview = memory("B_POSITIVE", 3, BigDecimal.valueOf(0.6), 1); // <0.85, unconfirmed
        CareerDecisionMemory lowConfidence = memory("C_POSITIVE", 2, BigDecimal.valueOf(0.3), 1); // <0.5 too
        when(repo.findActiveByUserId(eq(userId), any(Instant.class)))
                .thenReturn(List.of(verified, needsReview, lowConfidence));

        var summary = service(repo, mock(JobRepository.class), true).summaryFor(userId);

        assertEquals(3, summary.totalMemories());
        assertEquals(1, summary.verifiedCount());
        assertEquals(2, summary.needsReviewCount(), "needsReview counts unconfirmed + below 0.85: B and C");
        assertEquals(1, summary.lowConfidenceCount(), "only C is below 0.5");
    }

    @Test
    void summaryForDetectsConflictingPolarityInSameCategory() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        CareerDecisionMemory likesReact = CareerDecisionMemory.builder().id(UUID.randomUUID()).userId(userId)
                .decisionType("TECHNOLOGY_POSITIVE").category("TECHNOLOGY").value("React")
                .confidence(BigDecimal.valueOf(0.9)).createdAt(Instant.now().minus(5, ChronoUnit.DAYS)).build();
        CareerDecisionMemory dislikesReact = CareerDecisionMemory.builder().id(UUID.randomUUID()).userId(userId)
                .decisionType("TECHNOLOGY_NEGATIVE").category("TECHNOLOGY").value("React")
                .confidence(BigDecimal.valueOf(0.9)).createdAt(Instant.now()).build();
        when(repo.findActiveByUserId(eq(userId), any(Instant.class)))
                .thenReturn(List.of(likesReact, dislikesReact));

        var summary = service(repo, mock(JobRepository.class), true).summaryFor(userId);

        assertEquals(2, summary.conflictingCount(), "both rows belong to a category with two opposite polarities");
    }

    @Test
    void confirmSetsUserConfirmedOnlyForOwner() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        UUID memoryId = UUID.randomUUID();
        CareerDecisionMemory row = memory("X_POSITIVE", 3, BigDecimal.ONE, 1);
        row.setId(memoryId);
        when(repo.findById(memoryId)).thenReturn(Optional.of(row));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service(repo, mock(JobRepository.class), true).confirm(userId, memoryId);

        assertTrue(result.isPresent());
        assertTrue(result.get().getUserConfirmed());
    }

    @Test
    void confirmReturnsEmptyForNonOwner() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        UUID memoryId = UUID.randomUUID();
        CareerDecisionMemory row = memory("X_POSITIVE", 3, BigDecimal.ONE, 1); // owned by userId
        row.setId(memoryId);
        when(repo.findById(memoryId)).thenReturn(Optional.of(row));

        var result = service(repo, mock(JobRepository.class), true).confirm(UUID.randomUUID(), memoryId);

        assertTrue(result.isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void forgetSoftExpiresNeverHardDeletes() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        UUID memoryId = UUID.randomUUID();
        CareerDecisionMemory row = memory("X_POSITIVE", 3, BigDecimal.ONE, 1);
        row.setId(memoryId);
        assertNull(row.getExpiresAt());
        when(repo.findById(memoryId)).thenReturn(Optional.of(row));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service(repo, mock(JobRepository.class), true).forget(userId, memoryId);

        assertTrue(result.isPresent());
        assertNotNull(result.get().getExpiresAt(), "forget must soft-expire, not delete");
        verify(repo, never()).deleteById(any());
        verify(repo, never()).delete(any());
    }

    @Test
    void recordUserEditIsANoOpWhenDisabled() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        var result = service(repo, mock(JobRepository.class), false).recordUserEdit(userId, "COUNTRY", "Germany", "moving");
        assertTrue(result.isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void recordUserEditRejectsBlankValue() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        var result = service(repo, mock(JobRepository.class), true).recordUserEdit(userId, "COUNTRY", "  ", null);
        assertTrue(result.isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void recordUserEditWritesANewRowStampedAsExplicitAndVerified() {
        CareerDecisionMemoryRepository repo = mock(CareerDecisionMemoryRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service(repo, mock(JobRepository.class), true).recordUserEdit(userId, "country", "Germany", "relocating");

        assertTrue(result.isPresent());
        CareerDecisionMemory saved = result.get();
        assertEquals("COUNTRY", saved.getCategory(), "category is normalized to upper case");
        assertEquals("COUNTRY_POSITIVE", saved.getDecisionType());
        assertEquals("Germany", saved.getValue());
        assertEquals("relocating", saved.getReason());
        assertEquals("USER_EDIT", saved.getSource());
        assertFalse(saved.getAiGenerated(), "a direct user edit is never AI-generated");
        assertTrue(saved.getUserConfirmed(), "a direct user edit is verified by construction");
        assertEquals(0, BigDecimal.ONE.compareTo(saved.getConfidence()));
        assertEquals((short) 5, saved.getImportance());
    }

    private CareerDecisionMemory memory(String type, int importance, BigDecimal confidence, int ageDays) {
        return CareerDecisionMemory.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .decisionType(type)
                .category("CAREER")
                .confidence(confidence)
                .importance((short) importance)
                .source("TEST")
                .createdAt(Instant.now().minus(ageDays, ChronoUnit.DAYS))
                .build();
    }
}
