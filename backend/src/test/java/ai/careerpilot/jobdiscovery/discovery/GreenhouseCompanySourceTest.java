package ai.careerpilot.jobdiscovery.discovery;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests of {@code mapResponse} — package-private specifically so these can run
 * without standing up a mock HTTP server, matching this repo's no-@SpringBootTest convention
 * (see AshbyProviderTest/WorkdayProviderTest).
 */
class GreenhouseCompanySourceTest {

    private GreenhouseCompanySource source(boolean enabled) {
        return new GreenhouseCompanySource("https://boards-api.greenhouse.io/v1", "test-agent", enabled);
    }

    @Test
    void disabledByDefault() {
        var s = source(false);
        assertFalse(s.isConfigured());
        assertEquals("greenhouse", s.name());
    }

    @Test
    void enabledIsConfigured() {
        assertTrue(source(true).isConfigured());
    }

    @Test
    void mapResponseReturnsEmptyWhenBodyIsNull() {
        assertTrue(source(true).mapResponse(null, "acme").isEmpty());
    }

    @Test
    void mapResponseReturnsEmptyWhenJobsKeyMissing() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("something-else", "x");
        assertTrue(source(true).mapResponse(body, "acme").isEmpty());
    }

    @Test
    void mapResponseReturnsEmptyWhenJobsIsNotAList() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobs", "not-a-list");
        assertTrue(source(true).mapResponse(body, "acme").isEmpty());
    }

    @Test
    void mapResponseHitsOnWellFormedEmptyJobsArray() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobs", List.of());

        var result = source(true).mapResponse(body, "acme");

        assertTrue(result.isPresent());
        DiscoveredCandidate c = result.get();
        assertEquals("GREENHOUSE", c.atsType());
        assertEquals("acme", c.companyName());
        assertEquals("https://boards.greenhouse.io/acme", c.careerUrl());
        assertNull(c.tenant());
        assertNull(c.cluster());
        assertNull(c.site());
    }

    @Test
    void mapResponseHitsOnNonEmptyJobsArray() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobs", List.of(Map.of("id", 1, "title", "Engineer")));

        var result = source(true).mapResponse(body, "acme");
        assertTrue(result.isPresent());
    }

    @Test
    void probeReturnsEmptyForBlankSlug() {
        assertTrue(source(true).probe("").isEmpty());
        assertTrue(source(true).probe(null).isEmpty());
    }
}
