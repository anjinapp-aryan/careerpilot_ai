package ai.careerpilot.autopilot.resume;

import ai.careerpilot.autopilot.resume.ResumeSelectionEngine.ResumeSelection;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.repo.ResumeLearningRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResumeSelectionEngineTest {

    private ResumeRepository resumes;
    private ResumeTailoringRepository tailorings;
    private ResumeLearningRepository resumeLearning;
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resumes = mock(ResumeRepository.class);
        tailorings = mock(ResumeTailoringRepository.class);
        resumeLearning = mock(ResumeLearningRepository.class);
        when(resumeLearning.findByUserIdAndBestVersionTrue(any())).thenReturn(Optional.empty());
    }

    private ResumeSelectionEngine engine(boolean enabled) {
        return new ResumeSelectionEngine(resumes, tailorings, resumeLearning, enabled, 70);
    }

    private ResumeTailoring version(int v, Integer atsAfter) {
        return ResumeTailoring.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .tailoringVersion(v).atsAfter(atsAfter).status(ResumeTailoring.STATUS_GENERATED).build();
    }

    // ── pure evaluate() ──

    @Test
    void noBaseResumeYieldsNoBaseResume() {
        ResumeSelection s = engine(true).evaluate(false, List.of(), null);
        assertEquals(SelectionOutcome.NO_BASE_RESUME, s.outcome());
        assertNull(s.tailoringId());
    }

    @Test
    void noTailoredVersionNeedsTailoring() {
        assertEquals(SelectionOutcome.NEEDS_TAILORING,
                engine(true).evaluate(true, List.of(), null).outcome());
    }

    @Test
    void bestAtsBelowFloorNeedsTailoring() {
        ResumeSelection s = engine(true).evaluate(true, List.of(version(1, 55), version(2, 65)), null);
        assertEquals(SelectionOutcome.NEEDS_TAILORING, s.outcome());
        assertEquals(65, s.atsScore());
        assertNull(s.tailoringId());
    }

    @Test
    void picksHighestAtsVersionAboveFloor() {
        ResumeTailoring v1 = version(1, 72);
        ResumeTailoring v2 = version(2, 88);
        ResumeTailoring v3 = version(3, 80);
        ResumeSelection s = engine(true).evaluate(true, List.of(v1, v2, v3), null);
        assertEquals(SelectionOutcome.SELECTED, s.outcome());
        assertEquals(v2.getId(), s.tailoringId());
        assertEquals(88, s.atsScore());
    }

    @Test
    void unknownAtsSortsLowestSoAScoredVersionWins() {
        ResumeTailoring vNull = version(3, null);
        ResumeTailoring vScored = version(1, 75);
        ResumeSelection s = engine(true).evaluate(true, List.of(vNull, vScored), null);
        assertEquals(SelectionOutcome.SELECTED, s.outcome());
        assertEquals(vScored.getId(), s.tailoringId());
    }

    @Test
    void reasonNotesWhenChosenMatchesLearnedBestVersion() {
        ResumeTailoring v = version(1, 90);
        ResumeSelection s = engine(true).evaluate(true, List.of(v), v.getId().toString());
        assertEquals(SelectionOutcome.SELECTED, s.outcome());
        assertTrue(s.reason().toLowerCase().contains("historically best"));
    }

    // ── select() wiring ──

    @Test
    void disabledIsNoOp() {
        assertTrue(engine(false).select(userId, jobId).isEmpty());
        verifyNoInteractions(resumes, tailorings);
    }

    @Test
    void selectGathersSignalsAndReturnsResult() {
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(Resume.builder().id(UUID.randomUUID()).userId(userId).build()));
        when(tailorings.findByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(List.of(version(1, 85)));
        ResumeLearning best = ResumeLearning.builder().userId(userId).resumeVersion("x").bestVersion(true).build();
        when(resumeLearning.findByUserIdAndBestVersionTrue(userId)).thenReturn(Optional.of(best));

        var out = engine(true).select(userId, jobId);
        assertTrue(out.isPresent());
        assertEquals(SelectionOutcome.SELECTED, out.get().outcome());
        assertEquals(85, out.get().atsScore());
    }
}
