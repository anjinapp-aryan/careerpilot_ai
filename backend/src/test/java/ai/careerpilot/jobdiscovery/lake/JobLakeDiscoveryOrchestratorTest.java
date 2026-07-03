package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.jobdiscovery.lake.JobLakeDiscoveryOrchestrator.RunSummary;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The dark-default safety property: with {@code JOB_LAKE_DISCOVERY_ENABLED=false} the orchestrator
 * must short-circuit BEFORE touching the database or any provider — so the shadow pipeline is truly
 * inert until explicitly enabled (ZERO behavior change on deploy).
 */
class JobLakeDiscoveryOrchestratorTest {

    @Test
    void disabledRunDoesNotTouchDatabaseOrProviders() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JobLakeDiscoveryOrchestrator orchestrator = new JobLakeDiscoveryOrchestrator(
                List.of(), null, null, null, null, null, null, null, jdbc, /*enabled*/ false);

        assertFalse(orchestrator.isEnabled());

        RunSummary summary = orchestrator.run();

        assertFalse(summary.ran());
        assertNull(summary.runId());
        assertEquals(0, summary.providersRun());
        verifyNoInteractions(jdbc); // no advisory lock, no connection borrowed
    }
}
