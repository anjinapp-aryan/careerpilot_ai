package ai.careerpilot.jobdiscovery.discovery;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmartRecruitersCompanySourceTest {

    private SmartRecruitersCompanySource source(boolean enabled) {
        return new SmartRecruitersCompanySource("https://api.smartrecruiters.com", "test-agent", enabled);
    }

    @Test
    void disabledByDefault() {
        var s = source(false);
        assertFalse(s.isConfigured());
        assertEquals("smartrecruiters", s.name());
    }

    @Test
    void mapResponseReturnsEmptyWhenBodyIsNull() {
        assertTrue(source(true).mapResponse(null, "globex").isEmpty());
    }

    @Test
    void mapResponseReturnsEmptyWhenContentKeyMissing() {
        Map<String, Object> body = new LinkedHashMap<>();
        assertTrue(source(true).mapResponse(body, "globex").isEmpty());
    }

    @Test
    void mapResponseFallsBackToSlugWhenContentEmpty() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", List.of());

        var result = source(true).mapResponse(body, "globex");

        assertTrue(result.isPresent());
        DiscoveredCandidate c = result.get();
        assertEquals("SMARTRECRUITERS", c.atsType());
        assertEquals("globex", c.companyName());
        assertEquals("https://jobs.smartrecruiters.com/globex", c.careerUrl());
    }

    @Test
    void mapResponseUsesRealCompanyNameFromFirstPosting() {
        Map<String, Object> posting = new LinkedHashMap<>();
        posting.put("company", Map.of("name", "Globex Corporation"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", List.of(posting));

        var result = source(true).mapResponse(body, "globex");

        assertTrue(result.isPresent());
        assertEquals("Globex Corporation", result.get().companyName());
    }

    @Test
    void probeReturnsEmptyForBlankSlug() {
        assertTrue(source(true).probe("").isEmpty());
    }
}
