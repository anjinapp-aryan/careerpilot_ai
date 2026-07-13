package ai.careerpilot.jobdiscovery.provider;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dark-by-default gating (mirrors GreenhouseProviderTest/LeverProviderTest) plus direct unit
 * tests of the {@code mapPosting} record-mapping logic — package-private specifically so these
 * can run without standing up a mock HTTP server, matching this repo's no-@SpringBootTest
 * convention.
 */
class SmartRecruitersProviderTest {

    private SmartRecruitersProvider provider(boolean enabled, String companies) {
        return new SmartRecruitersProvider("https://api.smartrecruiters.com", "test-agent", enabled, companies, 0);
    }

    // ── isConfigured() gating ──────────────────────────────────────────────

    @Test
    void disabledByDefault() {
        var p = provider(false, "");
        assertFalse(p.isConfigured());
        assertEquals("smartrecruiters", p.name());
    }

    @Test
    void enabledButNoCompaniesStillNotConfigured() {
        var p = provider(true, "  , ,");
        assertFalse(p.isConfigured());
    }

    @Test
    void enabledWithCompaniesIsConfigured() {
        var p = provider(true, "12345,67890");
        assertTrue(p.isConfigured());
    }

    @Test
    void flagOffOverridesCompanies() {
        var p = provider(false, "12345");
        assertFalse(p.isConfigured());
    }

    // ── mapPosting() field mapping ─────────────────────────────────────────

    @Test
    void mapPostingReturnsNullWhenIdMissing() {
        var p = provider(true, "12345");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Backend Engineer");
        assertNull(p.mapPosting(m, "12345"));
    }

    @Test
    void mapPostingExtractsCoreFields() {
        var p = provider(true, "12345");
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("city", "Austin");
        location.put("region", "Texas");
        location.put("country", "us");
        location.put("remote", false);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-1");
        m.put("name", "Staff Engineer");
        m.put("location", location);
        m.put("releasedDate", "2025-02-01T00:00:00Z");
        m.put("applyUrl", "https://jobs.smartrecruiters.com/12345/posting-1");

        RawJob job = p.mapPosting(m, "12345");

        assertNotNull(job);
        assertEquals("posting-1", job.externalId());
        assertEquals("Staff Engineer", job.title());
        assertEquals("Austin, Texas, us", job.location());
        assertEquals("Austin", job.city());
        assertEquals("us", job.country());
        assertFalse(job.remote());
        assertEquals("https://jobs.smartrecruiters.com/12345/posting-1", job.sourceUrl());
        assertNotNull(job.postedDate());
    }

    @Test
    void mapPostingDetectsRemoteFlagInLocation() {
        var p = provider(true, "12345");
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("remote", true);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-2");
        m.put("name", "Engineer");
        m.put("location", location);

        RawJob job = p.mapPosting(m, "12345");
        assertTrue(job.remote());
        assertEquals("Remote", job.location());
    }

    @Test
    void mapPostingCompanyNameFromCompanyObjectWhenPresent() {
        var p = provider(true, "12345");
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("name", "Acme Corp");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-3");
        m.put("name", "Engineer");
        m.put("company", company);

        RawJob job = p.mapPosting(m, "12345");
        assertEquals("Acme Corp", job.company());
    }

    @Test
    void mapPostingCompanyNameFallsBackToCompanyIdWhenNoCompanyObject() {
        var p = provider(true, "12345");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-4");
        m.put("name", "Engineer");

        RawJob job = p.mapPosting(m, "12345");
        assertEquals("12345", job.company());
    }

    @Test
    void mapPostingSourceUrlFallsBackToRefJobAdUrlWhenApplyUrlMissing() {
        var p = provider(true, "12345");
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("jobAdUrl", "https://careers.acme.com/posting-5");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-5");
        m.put("name", "Engineer");
        m.put("ref", ref);

        RawJob job = p.mapPosting(m, "12345");
        assertEquals("https://careers.acme.com/posting-5", job.sourceUrl());
    }

    @Test
    void mapPostingExtractsAndStripsJobAdSectionsText() {
        var p = provider(true, "12345");
        Map<String, Object> jobDescriptionSection = new LinkedHashMap<>();
        jobDescriptionSection.put("text", "<p>We build <b>cool</b> stuff.</p>");
        Map<String, Object> qualificationsSection = new LinkedHashMap<>();
        qualificationsSection.put("text", "<ul><li>5+ years</li></ul>");
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("jobDescription", jobDescriptionSection);
        sections.put("qualifications", qualificationsSection);
        Map<String, Object> jobAd = new LinkedHashMap<>();
        jobAd.put("sections", sections);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-6");
        m.put("name", "Engineer");
        m.put("jobAd", jobAd);

        RawJob job = p.mapPosting(m, "12345");
        assertNotNull(job.description());
        assertTrue(job.description().contains("We build cool stuff."));
        assertTrue(job.description().contains("5+ years"));
    }

    @Test
    void mapPostingHandlesMissingJobAdGracefully() {
        var p = provider(true, "12345");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-7");
        m.put("name", "Engineer");

        RawJob job = p.mapPosting(m, "12345");
        assertNull(job.description());
    }

    @Test
    void mapPostingHandlesMissingLocationGracefully() {
        var p = provider(true, "12345");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-8");
        m.put("name", "Engineer");

        RawJob job = p.mapPosting(m, "12345");
        assertNull(job.location());
        assertFalse(job.remote());
    }

    @Test
    void mapPostingCarriesRawPayloadThrough() {
        var p = provider(true, "12345");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-9");
        m.put("name", "Engineer");

        RawJob job = p.mapPosting(m, "12345");
        assertSame(m, job.rawPayload());
    }

    @Test
    void mapPostingToleratesUnparseableReleasedDate() {
        var p = provider(true, "12345");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-10");
        m.put("name", "Engineer");
        m.put("releasedDate", "garbage");

        RawJob job = p.mapPosting(m, "12345");
        assertNull(job.postedDate());
    }

    @Test
    void multipleCompaniesConfiguredCsvParsedCorrectly() {
        var p = provider(true, " 111 , 222 ,, 333 ");
        assertTrue(p.isConfigured());
    }

    @Test
    void mapPostingJoinsPartialLocationFieldsOnly() {
        var p = provider(true, "12345");
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("country", "de");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "posting-11");
        m.put("name", "Engineer");
        m.put("location", location);

        RawJob job = p.mapPosting(m, "12345");
        assertEquals("de", job.location());
    }
}
