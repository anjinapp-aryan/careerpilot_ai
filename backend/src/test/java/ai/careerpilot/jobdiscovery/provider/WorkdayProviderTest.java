package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Dark-by-default gating (including the enterprise master switch) and job mapping for Workday. */
class WorkdayProviderTest {

    private static WorkdayProvider provider(boolean enabled, boolean enterpriseEnabled, String companies) {
        return new WorkdayProvider(enabled, enterpriseEnabled, companies, 50, 0, 0, "test-agent");
    }

    @Test
    void disabledByDefault() {
        var p = provider(false, false, "");
        assertFalse(p.isConfigured());
        assertEquals("workday", p.name());
    }

    @Test
    void enabledButNoCompaniesStillNotConfigured() {
        var p = provider(true, true, "  , ,");
        assertFalse(p.isConfigured());
    }

    @Test
    void masterSwitchOffOverridesProviderFlag() {
        // Provider flag on, companies configured, but the framework-wide master switch is off.
        var p = provider(true, false, "nvidia;wd5;NVIDIAExternalCareerSite");
        assertFalse(p.isConfigured());
    }

    @Test
    void providerFlagOffOverridesMasterSwitch() {
        var p = provider(false, true, "nvidia;wd5;NVIDIAExternalCareerSite");
        assertFalse(p.isConfigured());
    }

    @Test
    void enabledWithCompaniesAndMasterSwitchIsConfigured() {
        var p = provider(true, true, "nvidia;wd5;NVIDIAExternalCareerSite;NVIDIA;US;Technology;1");
        assertTrue(p.isConfigured());
        assertEquals(1, p.companies().size());
    }

    @Test
    void mapJobExtractsFieldsFromRealShapedPayload() {
        var p = provider(true, true, "");
        var company = new WorkdayCompanyConnector("nvidia", "wd5", "NVIDIAExternalCareerSite", "NVIDIA", "US", "Technology", 1);
        // Shape verified live against nvidia.wd5.myworkdayjobs.com during implementation.
        Map<String, Object> raw = Map.of(
                "title", "Senior Datacenter Technical Program Manager",
                "externalPath", "/job/US-CA-Santa-Clara/Datacenter-Technical-Program-Manager_JR2011480",
                "locationsText", "3 Locations",
                "postedOn", "Posted Today",
                "bulletFields", java.util.List.of("JR2011480"));

        var job = p.mapJob(raw, company);
        assertNotNull(job);
        assertEquals("/job/US-CA-Santa-Clara/Datacenter-Technical-Program-Manager_JR2011480", job.externalId());
        assertEquals("Senior Datacenter Technical Program Manager", job.title());
        assertEquals("NVIDIA", job.company());
        assertEquals("3 Locations", job.location());
        assertEquals("US", job.country());
        assertNull(job.description()); // list endpoint doesn't expose it — see class javadoc
        assertTrue(job.sourceUrl().startsWith("https://nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite/job/"));
    }

    @Test
    void mapJobDetectsRemoteFromLocationText() {
        var p = provider(true, true, "");
        var company = new WorkdayCompanyConnector("acme", "wd1", "External", "Acme", null, null, 0);
        Map<String, Object> raw = Map.of(
                "title", "Remote Engineer",
                "externalPath", "/job/Remote/Engineer_JR1",
                "locationsText", "US, Remote");

        var job = p.mapJob(raw, company);
        assertNotNull(job);
        assertTrue(job.remote());
    }

    @Test
    void mapJobReturnsNullWithoutExternalPath() {
        var p = provider(true, true, "");
        var company = new WorkdayCompanyConnector("acme", "wd1", "External", "Acme", null, null, 0);
        assertNull(p.mapJob(Map.of("title", "No Path"), company));
    }
}
