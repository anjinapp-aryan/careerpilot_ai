package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Dark-by-default gating: the provider must not be configured unless BOTH the flag and at least one board are set. */
class GreenhouseProviderTest {

    @Test
    void disabledByDefault() {
        var p = new GreenhouseProvider("https://boards-api.greenhouse.io/v1", "test-agent", false, "", 0);
        assertFalse(p.isConfigured());
        assertEquals("greenhouse", p.name());
    }

    @Test
    void enabledButNoBoardsStillNotConfigured() {
        var p = new GreenhouseProvider("https://boards-api.greenhouse.io/v1", "test-agent", true, "  , ,", 0);
        assertFalse(p.isConfigured());
    }

    @Test
    void enabledWithBoardsIsConfigured() {
        var p = new GreenhouseProvider("https://boards-api.greenhouse.io/v1", "test-agent", true, "airbnb,stripe", 0);
        assertTrue(p.isConfigured());
    }

    @Test
    void flagOffOverridesBoards() {
        var p = new GreenhouseProvider("https://boards-api.greenhouse.io/v1", "test-agent", false, "airbnb", 0);
        assertFalse(p.isConfigured());
    }
}
