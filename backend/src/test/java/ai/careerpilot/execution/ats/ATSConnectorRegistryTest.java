package ai.careerpilot.execution.ats;

import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.connector.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2E.3 — the connector registry routes a job to its ATS by URL, but in this build NO connector
 * is configured, so real submission is never possible. Tests cover detection routing per host token,
 * the zero-configured invariant, and that each stub connector throws on submit.
 */
class ATSConnectorRegistryTest {

    private final List<ATSConnector> connectors = List.of(
            new GreenhouseConnector(), new LeverConnector(), new WorkdayConnector(),
            new LinkedInConnector(), new SmartRecruitersConnector(), new AshbyConnector(),
            new BambooHrConnector());
    private final ATSConnectorRegistry registry = new ATSConnectorRegistry(connectors);

    private Job jobWithUrl(String url) {
        return Job.builder().id(UUID.randomUUID()).title("Eng").company("Acme").sourceUrl(url).build();
    }

    @Test
    void allSevenConnectorsAreRegistered() {
        assertThat(registry.all()).hasSize(7);
    }

    @Test
    void noConnectorIsConfiguredInThisBuild() {
        assertThat(registry.configuredCount()).isZero();
        assertThat(registry.all()).allMatch(c -> !c.isConfigured());
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
    void everyConnectorThrowsOnSubmit() {
        Job job = jobWithUrl("https://x");
        assertThat(connectors).allSatisfy(c -> {
            try {
                c.submit(job, java.util.Map.of());
                assertThat(false).as("expected %s.submit to throw", c.name()).isTrue();
            } catch (UnsupportedOperationException expected) {
                // correct — inert stub
            }
        });
    }

    @Test
    void everyConnectorThrowsOnAuthenticateExtractAndTrack() {
        ATSConnector c = new GreenhouseConnector();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.authenticate(java.util.Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.extractForm(jobWithUrl("x")))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.track("ref"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void detectMatchesOnSourceFieldToo() {
        Job job = Job.builder().id(UUID.randomUUID()).title("Eng").company("Acme")
                .source("greenhouse.io").build();
        assertThat(registry.detect(job)).isNotNull();
    }
}
