package ai.careerpilot.resumetailoring.version;

import ai.careerpilot.repo.ResumeTailoringRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2D.1 Step 3 — tailoring_version is a plain integer scoped to (user, job), rendered as
 * "v1.N" at the API layer only (see the module's versioning decision).
 */
class ResumeVersionManagerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Test
    void firstGenerationForAJobIsVersionOne() {
        ResumeTailoringRepository repo = mock(ResumeTailoringRepository.class);
        when(repo.countByUserIdAndJobId(userId, jobId)).thenReturn(0L);
        assertEquals(1, new ResumeVersionManager(repo).nextVersion(userId, jobId));
    }

    @Test
    void subsequentGenerationsIncrement() {
        ResumeTailoringRepository repo = mock(ResumeTailoringRepository.class);
        when(repo.countByUserIdAndJobId(userId, jobId)).thenReturn(2L);
        assertEquals(3, new ResumeVersionManager(repo).nextVersion(userId, jobId));
    }

    @Test
    void rendersAsV1DotN() {
        assertEquals("v1.1", ResumeVersionManager.render(1));
        assertEquals("v1.3", ResumeVersionManager.render(3));
    }
}
