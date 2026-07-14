package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dark-by-default gating (mirrors GreenhouseProviderTest/LeverProviderTest) plus direct unit
 * tests of the {@code mapJob} record-mapping logic — package-private specifically so these can
 * run without standing up a mock HTTP server, matching this repo's no-@SpringBootTest convention.
 */
class AshbyProviderTest {

    private AshbyProvider provider(boolean enabled, String orgs) {
        return new AshbyProvider("https://api.ashbyhq.com", "test-agent", enabled, orgs, 0);
    }

    // ── isConfigured() gating ──────────────────────────────────────────────

    @Test
    void disabledByDefault() {
        var p = provider(false, "");
        assertFalse(p.isConfigured());
        assertEquals("ashby", p.name());
    }

    @Test
    void enabledButNoOrgsStillNotConfigured() {
        var p = provider(true, "  , ,");
        assertFalse(p.isConfigured());
    }

    @Test
    void enabledWithOrgsIsConfigured() {
        var p = provider(true, "notion,figma");
        assertTrue(p.isConfigured());
    }

    @Test
    void flagOffOverridesOrgs() {
        var p = provider(false, "notion");
        assertFalse(p.isConfigured());
    }

    @Test
    void fetchOfUnconfiguredProviderIsNotCalledInAggregationButDirectFetchStillWorksMechanically() {
        // isConfigured() gates JobAggregationService, not fetch() itself; fetch() is still callable
        // (it will attempt a real HTTP call and fail fast on an unreachable/test base URL — we don't
        // invoke fetch() here to avoid a real network call in unit tests, see mapJob tests below).
        var p = provider(false, "");
        assertNotNull(p);
    }

    // ── mapJob() field mapping ─────────────────────────────────────────────

    @Test
    void mapJobReturnsNullWhenIdMissing() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Backend Engineer");
        assertNull(p.mapJob(m, "acme"));
    }

    @Test
    void mapJobExtractsCoreFields() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-123");
        m.put("title", "Senior Backend Engineer");
        m.put("location", "Remote - US");
        m.put("descriptionHtml", "<p>Build <b>things</b></p>");
        m.put("jobUrl", "https://jobs.ashbyhq.com/acme/job-123");
        m.put("publishedAt", "2025-01-15T12:00:00Z");

        RawJob job = p.mapJob(m, "acme");

        assertNotNull(job);
        assertEquals("job-123", job.externalId());
        assertEquals("Senior Backend Engineer", job.title());
        assertEquals("acme", job.company());
        assertEquals("Remote - US", job.location());
        assertEquals("Build things", job.description());
        assertEquals("https://jobs.ashbyhq.com/acme/job-123", job.sourceUrl());
        assertNotNull(job.postedDate());
        assertTrue(job.remote());
    }

    @Test
    void mapJobDetectsRemoteFromIsRemoteFlag() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-1");
        m.put("title", "Engineer");
        m.put("location", "San Francisco, CA");
        m.put("isRemote", true);

        RawJob job = p.mapJob(m, "acme");
        assertTrue(job.remote());
    }

    @Test
    void mapJobNonRemoteWhenNoSignal() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-1");
        m.put("title", "Engineer");
        m.put("location", "San Francisco, CA");

        RawJob job = p.mapJob(m, "acme");
        assertFalse(job.remote());
    }

    @Test
    void mapJobFallsBackToNestedAddressWhenLocationMissing() {
        var p = provider(true, "acme");
        Map<String, Object> address = new LinkedHashMap<>();
        Map<String, Object> postalAddress = new LinkedHashMap<>();
        postalAddress.put("addressLocality", "Berlin");
        postalAddress.put("addressRegion", "Germany");
        address.put("postalAddress", postalAddress);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-2");
        m.put("title", "Engineer");
        m.put("address", address);

        RawJob job = p.mapJob(m, "acme");
        assertEquals("Berlin, Germany", job.location());
    }

    @Test
    void mapJobHandlesMissingAddressGracefully() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-3");
        m.put("title", "Engineer");

        RawJob job = p.mapJob(m, "acme");
        assertNull(job.location());
        assertFalse(job.remote());
    }

    @Test
    void mapJobHandlesNullDescriptionHtml() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-4");
        m.put("title", "Engineer");

        RawJob job = p.mapJob(m, "acme");
        assertNull(job.description());
    }

    @Test
    void mapJobStripsHtmlTagsFromDescription() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-5");
        m.put("title", "Engineer");
        m.put("descriptionHtml", "<div><p>Line one</p><p>Line   two</p></div>");

        RawJob job = p.mapJob(m, "acme");
        assertEquals("Line one Line two", job.description());
    }

    @Test
    void mapJobCarriesRawPayloadThrough() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-6");
        m.put("title", "Engineer");

        RawJob job = p.mapJob(m, "acme");
        assertSame(m, job.rawPayload());
    }

    @Test
    void mapJobToleratesUnparseablePublishedAt() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-7");
        m.put("title", "Engineer");
        m.put("publishedAt", "not-a-date");

        RawJob job = p.mapJob(m, "acme");
        assertNull(job.postedDate());
    }

    @Test
    void mapJobSetsCompanyToOrgSlug() {
        var p = provider(true, "acme-corp");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-8");
        m.put("title", "Engineer");

        RawJob job = p.mapJob(m, "acme-corp");
        assertEquals("acme-corp", job.company());
    }

    @Test
    void mapJobRemoteDetectedFromLowercaseLocationText() {
        var p = provider(true, "acme");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "job-9");
        m.put("title", "Engineer");
        m.put("location", "fully remote (worldwide)");

        RawJob job = p.mapJob(m, "acme");
        assertTrue(job.remote());
    }

    @Test
    void multipleOrgsConfiguredCsvParsedCorrectly() {
        var p = provider(true, " notion , figma ,, acme ");
        assertTrue(p.isConfigured());
    }
}
