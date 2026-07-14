package ai.careerpilot.jobdiscovery.discovery;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkdayCompanySourceTest {

    private WorkdayCompanySource source(boolean enabled) {
        return new WorkdayCompanySource("test-agent", enabled);
    }

    @Test
    void disabledByDefault() {
        var s = source(false);
        assertFalse(s.isConfigured());
        assertEquals("workday", s.name());
    }

    // ── parseTriple() ───────────────────────────────────────────────────────

    @Test
    void parseTripleReturnsNullForBlankOrNull() {
        assertNull(WorkdayCompanySource.parseTriple(null));
        assertNull(WorkdayCompanySource.parseTriple(""));
        assertNull(WorkdayCompanySource.parseTriple("  "));
    }

    @Test
    void parseTripleReturnsNullWhenFieldsMissing() {
        assertNull(WorkdayCompanySource.parseTriple("nvidia;wd5"));
        assertNull(WorkdayCompanySource.parseTriple("nvidia;;External"));
    }

    @Test
    void parseTripleParsesValidEntry() {
        String[] parts = WorkdayCompanySource.parseTriple("nvidia;wd5;NVIDIAExternalCareerSite");
        assertNotNull(parts);
        assertArrayEquals(new String[] {"nvidia", "wd5", "NVIDIAExternalCareerSite"}, parts);
    }

    @Test
    void probeReturnsEmptyForMalformedCandidate() {
        assertTrue(source(true).probe("nvidia;wd5").isEmpty());
    }

    // ── mapResponse() ───────────────────────────────────────────────────────

    @Test
    void mapResponseReturnsEmptyWhenBodyIsNull() {
        assertTrue(source(true).mapResponse(null, "nvidia", "wd5", "External").isEmpty());
    }

    @Test
    void mapResponseReturnsEmptyWhenJobPostingsKeyMissing() {
        Map<String, Object> body = new LinkedHashMap<>();
        assertTrue(source(true).mapResponse(body, "nvidia", "wd5", "External").isEmpty());
    }

    @Test
    void mapResponseHitsOnWellFormedJobPostings() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobPostings", List.of(Map.of("title", "Engineer")));

        var result = source(true).mapResponse(body, "nvidia", "wd5", "External");

        assertTrue(result.isPresent());
        DiscoveredCandidate c = result.get();
        assertEquals("WORKDAY", c.atsType());
        assertEquals("nvidia", c.companyName());
        assertEquals("nvidia", c.tenant());
        assertEquals("wd5", c.cluster());
        assertEquals("External", c.site());
        assertEquals("https://nvidia.wd5.myworkdayjobs.com/External", c.careerUrl());
    }
}
