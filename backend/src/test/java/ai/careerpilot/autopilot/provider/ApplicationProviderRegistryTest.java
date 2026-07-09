package ai.careerpilot.autopilot.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationProviderRegistryTest {

    private ApplicationProviderRegistry registry() {
        return new ApplicationProviderRegistry(List.of(
                new GreenhouseProvider(), new LeverProvider(), new WorkdayProvider(),
                new SmartRecruitersProvider(), new AshbyProvider(), new LinkedInEasyApplyProvider(),
                new CompanyPortalProvider()));
    }

    @Test
    void resolvesSpecificAtsByHost() {
        assertEquals("greenhouse", registry().providerNameFor("https://boards.greenhouse.io/acme/jobs/123"));
        assertEquals("lever", registry().providerNameFor("https://jobs.lever.co/acme/abc"));
        assertEquals("workday", registry().providerNameFor("https://acme.wd5.myworkdayjobs.com/careers"));
        assertEquals("smartrecruiters", registry().providerNameFor("https://jobs.smartrecruiters.com/acme/1"));
        assertEquals("ashby", registry().providerNameFor("https://jobs.ashbyhq.com/acme/xyz"));
        assertEquals("linkedin-easy-apply", registry().providerNameFor("https://www.linkedin.com/jobs/view/123"));
    }

    @Test
    void unknownHostFallsBackToCompanyPortal() {
        assertEquals("company-portal", registry().providerNameFor("https://careers.acme.com/apply/42"));
    }

    @Test
    void noUrlResolvesToEmptyAndUnknownName() {
        assertTrue(registry().resolve(null).isEmpty());
        assertTrue(registry().resolve("  ").isEmpty());
        assertEquals("unknown", registry().providerNameFor(null));
    }

    @Test
    void everyProviderRoutesToHumanReviewToday() {
        for (String url : List.of("https://boards.greenhouse.io/x", "https://jobs.lever.co/y",
                "https://careers.acme.com/z")) {
            ApplicationProvider p = registry().resolve(url).orElseThrow();
            assertFalse(p.autoSubmitConfigured(), p.name() + " must not claim auto-submit");
            SubmissionResult r = p.submit(new ApplicationSubmissionRequest(null, null, url, null));
            assertEquals(SubmissionStatus.HUMAN_REVIEW, r.status());
        }
    }

    @Test
    void providerNamesListedForDiagnostics() {
        assertTrue(registry().providerNames().contains("greenhouse"));
        assertEquals(7, registry().providerNames().size());
    }
}
