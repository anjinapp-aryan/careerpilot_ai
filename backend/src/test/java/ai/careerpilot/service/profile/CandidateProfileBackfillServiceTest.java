package ai.careerpilot.service.profile;

import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.service.profile.CandidateProfileService.BackfillOutcome;
import ai.careerpilot.service.profile.CandidateProfileBackfillService.BackfillReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Backfill orchestration: the dry-run sizes work without spending LLM budget, and the real run
 * dispatches per-user outcomes and tallies them. {@code throttle-ms=0} so the test never sleeps.
 */
class CandidateProfileBackfillServiceTest {

    private ResumeRepository resumes;
    private CandidateProfileService profileService;
    private CandidateProfileBackfillService backfill;

    @BeforeEach
    void setUp() {
        resumes = mock(ResumeRepository.class);
        profileService = mock(CandidateProfileService.class);
        backfill = new CandidateProfileBackfillService(resumes, profileService, 0);
    }

    @Test
    void dryRunCountsCandidatesAndCallsNoLlm() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID(), u3 = UUID.randomUUID();
        when(resumes.findDistinctUserIds()).thenReturn(List.of(u1, u2, u3));
        when(profileService.needsBackfill(u1)).thenReturn(true);
        when(profileService.needsBackfill(u2)).thenReturn(false);
        when(profileService.needsBackfill(u3)).thenReturn(true);

        BackfillReport report = backfill.run(true);

        assertTrue(report.dryRun());
        assertEquals(3, report.totalUsersWithResume());
        assertEquals(2, report.candidates());
        assertEquals(0, report.generated());
        verify(profileService, never()).backfillUser(any());   // no generation in a dry run
    }

    @Test
    void realRunDispatchesAndTalliesOutcomes() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID(), u3 = UUID.randomUUID(), u4 = UUID.randomUUID();
        when(resumes.findDistinctUserIds()).thenReturn(List.of(u1, u2, u3, u4));
        when(profileService.backfillUser(u1)).thenReturn(BackfillOutcome.GENERATED);
        when(profileService.backfillUser(u2)).thenReturn(BackfillOutcome.SKIPPED_CURRENT);
        when(profileService.backfillUser(u3)).thenReturn(BackfillOutcome.SKIPPED_NO_RESUME);
        when(profileService.backfillUser(u4)).thenReturn(BackfillOutcome.FAILED);

        BackfillReport report = backfill.run(false);

        assertFalse(report.dryRun());
        assertEquals(4, report.totalUsersWithResume());
        assertEquals(1, report.generated());
        assertEquals(1, report.skippedCurrent());
        assertEquals(1, report.skippedNoResume());
        assertEquals(1, report.failed());
    }

    @Test
    void aThrownOutcomeIsCountedAsFailedNotPropagated() {
        UUID u1 = UUID.randomUUID();
        when(resumes.findDistinctUserIds()).thenReturn(List.of(u1));
        when(profileService.backfillUser(u1)).thenThrow(new RuntimeException("boom"));

        BackfillReport report = backfill.run(false);

        assertEquals(1, report.failed());
        assertEquals(0, report.generated());
    }
}
