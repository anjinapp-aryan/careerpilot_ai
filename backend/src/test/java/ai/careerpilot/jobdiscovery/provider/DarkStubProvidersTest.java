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

    @Test
    void devItJobsIsAlwaysInertRegardlessOfFlag() {
        var enabled = new DevItJobsProvider(true);
        var disabled = new DevItJobsProvider(false);

        assertFalse(enabled.isConfigured());
        assertFalse(disabled.isConfigured());
        assertTrue(enabled.isFlagEnabled());
        assertFalse(disabled.isFlagEnabled());
        assertTrue(enabled.fetch().isEmpty());
        assertEquals("devitjobs", enabled.name());
    }

    @Test
    void graphqlJobsIsAlwaysInertRegardlessOfFlag() {
        var enabled = new GraphqlJobsProvider(true);
        var disabled = new GraphqlJobsProvider(false);

        assertFalse(enabled.isConfigured());
        assertFalse(disabled.isConfigured());
        assertTrue(enabled.isFlagEnabled());
        assertFalse(disabled.isFlagEnabled());
        assertTrue(enabled.fetch().isEmpty());
        assertEquals("graphql-jobs", enabled.name());
    }

    @Test
    void taleoIsAlwaysInertRegardlessOfFlagsButParsesConnectorConfig() {
        var enabled = new TaleoProvider(true, true, "Acme Corp;https://acme.taleo.net;US;Technology;1");
        var flagOffMaster = new TaleoProvider(true, false, "");
        var flagOffOwn = new TaleoProvider(false, true, "");

        assertFalse(enabled.isConfigured());
        assertTrue(enabled.isFlagEnabled());
        assertFalse(flagOffMaster.isFlagEnabled());
        assertFalse(flagOffOwn.isFlagEnabled());
        assertTrue(enabled.fetch().isEmpty());
        assertEquals("taleo", enabled.name());

        assertEquals(1, enabled.companies().size());
        assertEquals("Acme Corp", enabled.companies().get(0).company());
    }

    @Test
    void successFactorsIsAlwaysInertRegardlessOfFlagsButParsesConnectorConfig() {
        var enabled = new SuccessFactorsProvider(true, true, "Globex;https://globex.successfactors.eu;DE;Manufacturing;2");
        var flagOffMaster = new SuccessFactorsProvider(true, false, "");

        assertFalse(enabled.isConfigured());
        assertTrue(enabled.isFlagEnabled());
        assertFalse(flagOffMaster.isFlagEnabled());
        assertTrue(enabled.fetch().isEmpty());
        assertEquals("successfactors", enabled.name());

        assertEquals(1, enabled.companies().size());
        assertEquals("Globex", enabled.companies().get(0).company());
        assertEquals("DE", enabled.companies().get(0).country());
    }
}
