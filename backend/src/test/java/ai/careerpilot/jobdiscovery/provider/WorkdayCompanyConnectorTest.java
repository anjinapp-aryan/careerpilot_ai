package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkdayCompanyConnectorTest {

    @Test
    void parsesFullEntry() {
        var c = WorkdayCompanyConnector.parse("nvidia;wd5;NVIDIAExternalCareerSite;NVIDIA;US;Technology;1");
        assertNotNull(c);
        assertEquals("nvidia", c.tenant());
        assertEquals("wd5", c.cluster());
        assertEquals("NVIDIAExternalCareerSite", c.site());
        assertEquals("NVIDIA", c.displayName());
        assertEquals("US", c.country());
        assertEquals("Technology", c.industry());
        assertEquals(1, c.priority());
    }

    @Test
    void parsesMinimalEntryWithDefaults() {
        var c = WorkdayCompanyConnector.parse("acme;wd1;ExternalSite");
        assertNotNull(c);
        assertEquals("acme", c.tenant());
        assertNull(c.displayName());
        assertNull(c.country());
        assertEquals(0, c.priority());
        assertEquals("acme", c.companyName()); // falls back to tenant
    }

    @Test
    void missingRequiredFieldsReturnsNull() {
        assertNull(WorkdayCompanyConnector.parse("acme;wd1")); // missing site
        assertNull(WorkdayCompanyConnector.parse(""));
        assertNull(WorkdayCompanyConnector.parse(null));
    }

    @Test
    void urlsAreDerivedNotHardcoded() {
        var c = WorkdayCompanyConnector.parse("nvidia;wd5;NVIDIAExternalCareerSite");
        assertEquals("https://nvidia.wd5.myworkdayjobs.com/wday/cxs/nvidia/NVIDIAExternalCareerSite/jobs", c.jobsUrl());
        assertEquals("https://nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite", c.careerUrl());
    }
}
