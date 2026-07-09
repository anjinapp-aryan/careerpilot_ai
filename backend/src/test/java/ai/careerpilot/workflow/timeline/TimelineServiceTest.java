package ai.careerpilot.workflow.timeline;

import ai.careerpilot.domain.ApplicationTimeline;
import ai.careerpilot.repo.ApplicationTimelineRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Phase 3A.2 — the timeline service ships DARK (disabled → no-op), appends append-only, never throws. */
class TimelineServiceTest {

    private final ApplicationTimelineRepository repo = mock(ApplicationTimelineRepository.class);
    private final TimelineMetrics metrics = new TimelineMetrics();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @Test
    void disabledIsNoOp() {
        TimelineService svc = new TimelineService(repo, metrics, false);
        assertThat(svc.append(userId, jobId, "VIEWED", "STATUS_DETECTION", 1.0, "d")).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void appendPersistsWhenEnabled() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TimelineService svc = new TimelineService(repo, metrics, true);
        assertThat(svc.append(userId, jobId, "VIEWED", ApplicationTimeline.SOURCE_STATUS_DETECTION, 1.0, "d")).isPresent();
        verify(repo).save(any(ApplicationTimeline.class));
    }

    @Test
    void neverThrowsOnRepoFailure() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        TimelineService svc = new TimelineService(repo, metrics, true);
        assertThat(svc.append(userId, jobId, "VIEWED", "STATUS_DETECTION", 1.0, "d")).isEmpty();
    }
}
