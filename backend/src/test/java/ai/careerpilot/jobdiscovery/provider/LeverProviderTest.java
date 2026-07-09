package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Dark-by-default gating: the provider must not be configured unless BOTH the flag and at least one company are set. */
class LeverProviderTest {

    @Test
    void disabledByDefault() {
        var p = new LeverProvider("https://api.lever.co/v0", "test-agent", false, "", 0);
        assertFalse(p.isConfigured());
        assertEquals("lever", p.name());
    }

    @Test
    void enabledButNoCompaniesStillNotConfigured() {
        var p = new LeverProvider("https://api.lever.co/v0", "test-agent", true, "", 0);
        assertFalse(p.isConfigured());
    }

    @Test
    void enabledWithCompaniesIsConfigured() {
        var p = new LeverProvider("https://api.lever.co/v0", "test-agent", true, "netflix,figma", 0);
        assertTrue(p.isConfigured());
    }
}
