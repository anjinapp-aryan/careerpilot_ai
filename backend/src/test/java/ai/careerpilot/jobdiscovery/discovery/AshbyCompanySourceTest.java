package ai.careerpilot.jobdiscovery.discovery;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AshbyCompanySourceTest {

    private AshbyCompanySource source(boolean enabled) {
        return new AshbyCompanySource("https://api.ashbyhq.com", "test-agent", enabled);
    }

    @Test
    void disabledByDefault() {
        var s = source(false);
        assertFalse(s.isConfigured());
        assertEquals("ashby", s.name());
    }

    @Test
    void mapResponseReturnsEmptyWhenBodyIsNull() {
        assertTrue(source(true).mapResponse(null, "notion").isEmpty());
    }

    @Test
    void mapResponseReturnsEmptyWhenJobsKeyMissing() {
        Map<String, Object> body = new LinkedHashMap<>();
        assertTrue(source(true).mapResponse(body, "notion").isEmpty());
    }

    @Test
    void mapResponseHitsOnWellFormedJobsArray() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobs", List.of(Map.of("id", "job-1")));

        var result = source(true).mapResponse(body, "notion");

        assertTrue(result.isPresent());
        DiscoveredCandidate c = result.get();
        assertEquals("ASHBY", c.atsType());
        assertEquals("notion", c.companyName());
        assertEquals("https://jobs.ashbyhq.com/notion", c.careerUrl());
    }

    @Test
    void probeReturnsEmptyForBlankSlug() {
        assertTrue(source(true).probe(" ").isEmpty());
    }
}
