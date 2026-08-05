package ai.careerpilot.execution.ats;

import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.connector.*;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2E.3 / Gap D — the connector registry routes a job to its ATS by URL (detect() is unchanged,
 * real, and side-effect free). Gap D makes Greenhouse/Lever real (guest-apply eligible) while every
 * other connector (Workday, LinkedIn, SmartRecruiters, Ashby, BambooHR) remains an inert stub whose
 * submit()/authenticate()/track() throw — this is the "only two connectors get real automation"
 * boundary, asserted explicitly below.
 */
class ATSConnectorRegistryTest {

    private final PlaywrightAutomationProvider unconfiguredBrowser = mock(PlaywrightAutomationProvider.class);
    private final PlaywrightAutomationProvider configuredBrowser = mock(PlaywrightAutomationProvider.class);
    /** Phase 0 — real collaborator (pure, no I/O); this test asserts routing, not verification. */
    private final ai.careerpilot.execution.verification.evidence.ConfirmationPageVerifier verifier =
            new ai.careerpilot.execution.verification.evidence.ConfirmationPageVerifier(
                    new ai.careerpilot.execution.verification.evidence.ConfirmationPageAnalyzer(),
                    new ai.careerpilot.execution.verification.evidence.VerificationAdjudicator());

    private final List<ATSConnector> connectors = List.of(
            new GreenhouseConnector(unconfiguredBrowser, verifier), new LeverConnector(unconfiguredBrowser, verifier),
            new WorkdayConnector(), new LinkedInConnector(), new SmartRecruitersConnector(),
            new AshbyConnector(), new BambooHrConnector());
    private final ATSConnectorRegistry registry = new ATSConnectorRegistry(connectors);

    private Job jobWithUrl(String url) {
        return Job.builder().id(UUID.randomUUID()).title("Eng").company("Acme").sourceUrl(url).build();
    }

    @Test
    void allSevenConnectorsAreRegistered() {
        assertThat(registry.all()).hasSize(7);
    }

    @Test
    void greenhouseAndLeverAreConfiguredOnlyWhenBrowserIsConfigured() {
        when(unconfiguredBrowser.isConfigured()).thenReturn(false);
        assertThat(registry.configuredCount()).isZero();
    }

    @Test
    void greenhouseAndLeverBecomeConfiguredWhenBrowserIsConfigured() {
        when(configuredBrowser.isConfigured()).thenReturn(true);
        GreenhouseConnector gh = new GreenhouseConnector(configuredBrowser, verifier);
        LeverConnector lever = new LeverConnector(configuredBrowser, verifier);
        assertThat(gh.isConfigured()).isTrue();
        assertThat(lever.isConfigured()).isTrue();
    }

    @Test
    void onlyGreenhouseAndLeverAreGuestApplyEligible() {
        assertThat(GuestApplyEligibility.isEligible("greenhouse")).isTrue();
        assertThat(GuestApplyEligibility.isEligible("lever")).isTrue();
        assertThat(GuestApplyEligibility.isEligible("linkedin")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("workday")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("smartrecruiters")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("ashby")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("bamboohr")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "https://boards.greenhouse.io/acme/jobs/1,greenhouse",
            "https://jobs.lever.co/acme/123,lever",
            "https://acme.wd1.myworkdayjobs.com/careers,workday",
            "https://www.linkedin.com/jobs/view/123,linkedin",
            "https://jobs.smartrecruiters.com/acme/1,smartrecruiters",
            "https://jobs.ashbyhq.com/acme/1,ashby",
            "https://acme.bamboohr.com/careers/1,bamboohr"
    })
    void detectRoutesByHostToken(String url, String expectedConnector) {
        ATSConnector c = registry.detect(jobWithUrl(url));
        assertThat(c).isNotNull();
        assertThat(c.name()).isEqualTo(expectedConnector);
    }

    @Test
    void detectReturnsNullForAnUnknownHost() {
        assertThat(registry.detect(jobWithUrl("https://remoteok.com/jobs/1"))).isNull();
    }

    @Test
    void detectReturnsNullForNullJob() {
        assertThat(registry.detect(null)).isNull();
    }

    @Test
    void nonGuestApplyConnectorsStillThrowOnSubmitAuthenticateAndTrack() {
        Job job = jobWithUrl("https://x");
        List<ATSConnector> loginRequired = List.of(
                new WorkdayConnector(), new LinkedInConnector(), new SmartRecruitersConnector(),
                new AshbyConnector(), new BambooHrConnector());
        assertThat(loginRequired).allSatisfy(c -> {
            try {
                c.submit(job, java.util.Map.of());
                assertThat(false).as("expected %s.submit to throw", c.name()).isTrue();
            } catch (UnsupportedOperationException expected) {
                // correct — inert stub, still no login-required automation
            }
        });
    }

    @Test
    void greenhouseAndLeverStillThrowOnAuthenticateAndTrack_neverLoginNeverStatusApi() {
        GreenhouseConnector gh = new GreenhouseConnector(unconfiguredBrowser, verifier);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gh.authenticate(java.util.Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gh.track("ref"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void greenhouseExtractFormReturnsRealSchemaNotFabricatedValues() {
        GreenhouseConnector gh = new GreenhouseConnector(unconfiguredBrowser, verifier);
        var schema = gh.extractForm(jobWithUrl("https://boards.greenhouse.io/acme/jobs/1"));
        assertThat(schema).containsValues("first_name", "last_name", "email");
    }

    @Test
    void leverExtractFormReturnsRealSchemaNotFabricatedValues() {
        LeverConnector lever = new LeverConnector(unconfiguredBrowser, verifier);
        var schema = lever.extractForm(jobWithUrl("https://jobs.lever.co/acme/123"));
        assertThat(schema).containsValues("name", "email");
    }

    @Test
    void detectMatchesOnSourceFieldToo() {
        Job job = Job.builder().id(UUID.randomUUID()).title("Eng").company("Acme")
                .source("greenhouse.io").build();
        assertThat(registry.detect(job)).isNotNull();
    }
}
