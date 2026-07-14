package ai.careerpilot.jobdiscovery.enterprise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Entity Tests (Part 13) — builder defaults and field roundtrip. */
class CompanyConnectorTest {

    @Test
    void builderAppliesSensibleDefaults() {
        var c = CompanyConnector.builder()
                .companyName("Acme")
                .atsType("WORKDAY")
                .build();

        assertEquals(0, c.getPriority());
        assertTrue(c.getEnabled());
        assertEquals("NEVER_RUN", c.getHealthStatus());
        assertEquals(0, c.getFailureCount());
        assertEquals(0L, c.getAverageLatencyMs());
        assertEquals(0, c.getJobsImported());
    }

    @Test
    void settersRoundtrip() {
        var c = new CompanyConnector();
        c.setCompanyName("NVIDIA");
        c.setAtsType("WORKDAY");
        c.setTenant("nvidia");
        c.setCluster("wd5");
        c.setSite("NVIDIAExternalCareerSite");
        c.setEnabled(false);
        c.setHealthStatus("DOWN");
        c.setFailureCount(3);

        assertEquals("NVIDIA", c.getCompanyName());
        assertEquals("nvidia", c.getTenant());
        assertFalse(c.getEnabled());
        assertEquals("DOWN", c.getHealthStatus());
        assertEquals(3, c.getFailureCount());
    }
}
