package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5F/5G — Wellfound and the company-career-site crawlers are registered scaffolding only:
 * no real scraping is implemented, so they must always report unconfigured and fetch nothing,
 * regardless of their dark flag, to guarantee they can never silently start crawling.
 */
class DarkStubProvidersTest {

    @Test
    void wellfoundIsAlwaysInertRegardlessOfFlag() {
        var enabled = new WellfoundProvider(true);
        var disabled = new WellfoundProvider(false);

        assertFalse(enabled.isConfigured());
        assertFalse(disabled.isConfigured());
        assertTrue(enabled.isFlagEnabled());
        assertFalse(disabled.isFlagEnabled());
        assertTrue(enabled.fetch().isEmpty());
    }

    @Test
    void companyCareerSitesIsAlwaysInertRegardlessOfFlag() {
        var enabled = new CompanyCareerSiteProvider(true);

        assertFalse(enabled.isConfigured());
        assertTrue(enabled.isFlagEnabled());
        assertTrue(enabled.fetch().isEmpty());
        assertEquals(10, CompanyCareerSiteProvider.TARGET_COMPANIES.size());
    }
}
