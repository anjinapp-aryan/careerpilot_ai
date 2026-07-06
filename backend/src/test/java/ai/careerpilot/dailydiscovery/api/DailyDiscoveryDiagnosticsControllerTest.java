package ai.careerpilot.dailydiscovery.api;

import ai.careerpilot.dailydiscovery.DailyJobDiscoveryMetrics;
import ai.careerpilot.jobdiscovery.provider.CompanyCareerSiteProvider;
import ai.careerpilot.jobdiscovery.provider.GreenhouseProvider;
import ai.careerpilot.jobdiscovery.provider.LeverProvider;
import ai.careerpilot.jobdiscovery.provider.WellfoundProvider;
import ai.careerpilot.repo.JobFetchAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Stock (dark) defaults must report NOT_CONFIGURED everywhere — never fabricate an UP status. */
class DailyDiscoveryDiagnosticsControllerTest {

    private JobFetchAuditRepository audits;
    private DailyDiscoveryDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        audits = mock(JobFetchAuditRepository.class);
        when(audits.findTopByProviderOrderByStartedAtDesc(anyString())).thenReturn(Optional.empty());

        GreenhouseProvider greenhouse = new GreenhouseProvider("https://boards-api.greenhouse.io/v1", "ua", false, "", 0);
        LeverProvider lever = new LeverProvider("https://api.lever.co/v0", "ua", false, "", 0);
        WellfoundProvider wellfound = new WellfoundProvider(false);
        CompanyCareerSiteProvider companySites = new CompanyCareerSiteProvider(false);

        controller = new DailyDiscoveryDiagnosticsController(
                new DailyJobDiscoveryMetrics(), audits, greenhouse, lever, wellfound, companySites);
    }

    @Test
    void schedulerReportsNotConfiguredByDefault() {
        Map<String, Object> out = controller.scheduler();
        assertEquals(false, out.get("schedulerEnabled"));
        assertEquals("NOT_CONFIGURED", out.get("health"));
    }

    @Test
    void providersReportNotConfiguredOrNeverRunByDefault() {
        Map<String, Object> out = controller.providers();

        @SuppressWarnings("unchecked")
        Map<String, Object> greenhouse = (Map<String, Object>) out.get("greenhouse");
        assertEquals(false, greenhouse.get("configured"));
        assertEquals("NOT_CONFIGURED", greenhouse.get("health"));

        @SuppressWarnings("unchecked")
        Map<String, Object> remoteok = (Map<String, Object>) out.get("remoteok");
        assertEquals(true, remoteok.get("configured"));
        assertEquals("NEVER_RUN", remoteok.get("health")); // configured but no audit row yet

        @SuppressWarnings("unchecked")
        Map<String, Object> wellfound = (Map<String, Object>) out.get("wellfound");
        assertEquals(false, wellfound.get("configured"));
        assertEquals("NOT_CONFIGURED", wellfound.get("health"));
        assertNotNull(wellfound.get("reason"));

        @SuppressWarnings("unchecked")
        Map<String, Object> company = (Map<String, Object>) out.get("companyCareerSites");
        assertEquals(false, company.get("configured"));
        assertEquals("NOT_CONFIGURED", company.get("health"));
    }
}
