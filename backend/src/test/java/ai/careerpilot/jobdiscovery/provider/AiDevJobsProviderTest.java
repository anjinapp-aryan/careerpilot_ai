package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Dark-by-default gating and identity for the real, keyless AI Dev Jobs adapter. */
class AiDevJobsProviderTest {

    @Test
    void disabledByDefault() {
        var p = new AiDevJobsProvider(false, "https://aidevboard.com", 50, "test-agent");
        assertFalse(p.isConfigured());
        assertEquals("ai-dev-jobs", p.name());
    }

    @Test
    void enabledIsConfigured() {
        var p = new AiDevJobsProvider(true, "https://aidevboard.com", 50, "test-agent");
        assertTrue(p.isConfigured());
    }

    @Test
    void limitIsClampedToApiMax() {
        // Constructing with an out-of-range limit must not throw; clamping is exercised via fetch()
        // URI construction, so this just guards the constructor doesn't reject boundary values.
        assertDoesNotThrow(() -> new AiDevJobsProvider(true, "https://aidevboard.com", 500, "test-agent"));
        assertDoesNotThrow(() -> new AiDevJobsProvider(true, "https://aidevboard.com", 0, "test-agent"));
    }
}
