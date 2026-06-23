package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Converts a provider's {@link RawJob} into a persistable {@link Job} for the global
 * discovered pool ({@code org_id = NULL}). Responsibilities: strip HTML from descriptions,
 * infer country/city from a free-text location when the provider didn't supply them, and
 * extract skills from title+description when absent. Pure functions, no I/O.
 */
@Component
public class JobNormalizer {

    private static final int MAX_DESCRIPTION = 8000;

    /** Major-city → country, so "Bangalore" / "Berlin" resolve even without a country token. */
    private static final Map<String, String> CITY_COUNTRY = buildCityCountry();
    /** Country names/codes we recognise directly inside a location string. */
    private static final Map<String, String> COUNTRY_ALIASES = buildCountryAliases();

    private final JobScoring scoring;
    private final JobEnricher enricher;
    private final boolean enrichEnabled;

    public JobNormalizer(JobScoring scoring,
                         JobEnricher enricher,
                         @Value("${jobs.enrich.enabled:true}") boolean enrichEnabled) {
        this.scoring = scoring;
        this.enricher = enricher;
        this.enrichEnabled = enrichEnabled;
    }

    public Job toJob(RawJob raw, String source) {
        String description = truncate(stripHtml(raw.description()), MAX_DESCRIPTION);
        String location = blankToNull(raw.location());

        String country = firstNonBlank(raw.country(), inferCountry(location));
        String city = firstNonBlank(raw.city(), inferCity(location));

        List<String> skills = (raw.skills() != null && !raw.skills().isEmpty())
                ? raw.skills()
                : scoring.extractSkills((raw.title() == null ? "" : raw.title()) + " " + description);

        boolean remote = Boolean.TRUE.equals(raw.remote())
                || (location != null && location.toLowerCase().contains("remote"));

        Job job = Job.builder()
                .orgId(null) // global discovered pool
                .title(safe(raw.title(), "Untitled role"))
                .company(safe(raw.company(), "Unknown"))
                .location(location)
                .description(safe(description, "No description provided."))
                .source(source)
                .externalId(raw.externalId())
                .externalUrl(raw.sourceUrl())
                .sourceUrl(raw.sourceUrl())
                .country(country)
                .city(city)
                .remote(remote)
                .salaryMin(raw.salaryMin())
                .salaryMax(raw.salaryMax())
                .currency(raw.currency())
                .salaryRange(formatSalary(raw.salaryMin(), raw.salaryMax(), raw.currency()))
                .skills(skills.isEmpty() ? null : String.join(",", skills))
                .postedDate(raw.postedDate())
                .postedAt(raw.postedDate())
                .build();
        if (enrichEnabled) enricher.enrich(job);
        return job;
    }

    /** Copy mutable discovery fields from a freshly-normalized job onto an existing row (upsert). */
    public void merge(Job target, Job fresh) {
        target.setTitle(fresh.getTitle());
        target.setCompany(fresh.getCompany());
        target.setLocation(fresh.getLocation());
        target.setDescription(fresh.getDescription());
        target.setCountry(fresh.getCountry());
        target.setCity(fresh.getCity());
        target.setRemote(fresh.getRemote());
        target.setSalaryMin(fresh.getSalaryMin());
        target.setSalaryMax(fresh.getSalaryMax());
        target.setCurrency(fresh.getCurrency());
        target.setSalaryRange(fresh.getSalaryRange());
        target.setSkills(fresh.getSkills());
        target.setSourceUrl(fresh.getSourceUrl());
        target.setExternalUrl(fresh.getExternalUrl());
        target.setPostedDate(fresh.getPostedDate());
        target.setPostedAt(fresh.getPostedAt());
        // Carry over enrichment derived on the fresh copy (idempotent, keyword-based).
        target.setRemoteType(fresh.getRemoteType());
        target.setSponsorshipAvailable(fresh.getSponsorshipAvailable());
        target.setRelocationSupport(fresh.getRelocationSupport());
        target.setCompanySize(fresh.getCompanySize());
        target.setRequiredExperience(fresh.getRequiredExperience());
    }

    // ── inference helpers ──────────────────────────────────────────────────────────

    String inferCountry(String location) {
        if (location == null) return null;
        String loc = location.toLowerCase();
        for (Map.Entry<String, String> e : COUNTRY_ALIASES.entrySet()) {
            if (containsToken(loc, e.getKey())) return e.getValue();
        }
        for (Map.Entry<String, String> e : CITY_COUNTRY.entrySet()) {
            if (containsToken(loc, e.getKey())) return e.getValue();
        }
        return null;
    }

    String inferCity(String location) {
        if (location == null) return null;
        String loc = location.toLowerCase();
        for (String city : CITY_COUNTRY.keySet()) {
            if (containsToken(loc, city)) return capitalize(city);
        }
        // Fall back to the part before the first comma if it isn't a country token.
        String[] parts = location.split(",");
        if (parts.length > 0) {
            String head = parts[0].trim();
            if (!head.isEmpty() && inferCountryDirect(head.toLowerCase()) == null
                    && !head.equalsIgnoreCase("remote")) {
                return head;
            }
        }
        return null;
    }

    private String inferCountryDirect(String token) {
        return COUNTRY_ALIASES.entrySet().stream()
                .filter(e -> containsToken(token, e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static boolean containsToken(String haystack, String token) {
        // word-ish boundary so "us" doesn't match "industry"
        return haystack.matches(".*(^|[^a-z])" + java.util.regex.Pattern.quote(token) + "([^a-z]|$).*");
    }

    // ── formatting helpers ──────────────────────────────────────────────────────────

    static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String formatSalary(java.math.BigDecimal min, java.math.BigDecimal max, String currency) {
        if (min == null && max == null) return null;
        String cur = currency == null ? "" : currency + " ";
        if (min != null && max != null) return cur + compact(min) + " – " + compact(max);
        return cur + compact(min != null ? min : max);
    }

    private static String compact(java.math.BigDecimal v) {
        long n = v.longValue();
        if (n >= 1000) return (n / 1000) + "k";
        return String.valueOf(n);
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Arrays.stream(s.split(" "))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .reduce((a, b) -> a + " " + b).orElse(s);
    }

    private static Map<String, String> buildCountryAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("india", "India");
        m.put("united states", "United States");
        m.put("usa", "United States");
        m.put("u.s.", "United States");
        m.put("united kingdom", "United Kingdom");
        m.put("uk", "United Kingdom");
        m.put("canada", "Canada");
        m.put("germany", "Germany");
        m.put("deutschland", "Germany");
        m.put("netherlands", "Netherlands");
        m.put("singapore", "Singapore");
        m.put("australia", "Australia");
        m.put("united arab emirates", "United Arab Emirates");
        m.put("uae", "United Arab Emirates");
        m.put("ireland", "Ireland");
        m.put("france", "France");
        m.put("spain", "Spain");
        return m;
    }

    private static Map<String, String> buildCityCountry() {
        Map<String, String> m = new LinkedHashMap<>();
        // India
        for (String c : List.of("bangalore", "bengaluru", "hyderabad", "pune", "chennai",
                "mumbai", "delhi", "gurgaon", "gurugram", "noida", "kolkata", "ahmedabad"))
            m.put(c, "India");
        // United States
        for (String c : List.of("san francisco", "new york", "seattle", "austin", "boston",
                "chicago", "los angeles", "denver", "atlanta"))
            m.put(c, "United States");
        // United Kingdom
        for (String c : List.of("london", "manchester", "edinburgh", "cambridge"))
            m.put(c, "United Kingdom");
        // Germany / NL / others
        m.put("berlin", "Germany");
        m.put("munich", "Germany");
        m.put("amsterdam", "Netherlands");
        m.put("toronto", "Canada");
        m.put("vancouver", "Canada");
        m.put("sydney", "Australia");
        m.put("melbourne", "Australia");
        m.put("dubai", "United Arab Emirates");
        m.put("abu dhabi", "United Arab Emirates");
        return m;
    }
}
