package ai.careerpilot.jobdiscovery.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic, pre-persistence representation of a discovered job. Adapters map
 * their native payload into this; {@code JobNormalizer} fills gaps (country/city,
 * skills) and converts it into a {@code Job} entity for the global discovered pool.
 *
 * <p>{@code externalId} is the provider's stable id and, together with the source name,
 * forms the dedup key.
 *
 * <p>{@code rawPayload} is the provider's untouched source map, captured only so the
 * Phase 2A Job Lake can persist it ({@code job_raw} — "never discard provider data"). It is
 * <b>optional and ignored by the legacy {@code JobNormalizer}</b>: the 14-arg constructor
 * leaves it {@code null}, so existing providers/tests are unaffected.
 */
public record RawJob(
        String externalId,
        String title,
        String company,
        String location,
        String country,
        String city,
        Boolean remote,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String description,
        List<String> skills,
        String sourceUrl,
        Instant postedDate,
        Map<String, Object> rawPayload) {

    /** Backward-compatible constructor for callers that don't capture a raw payload. */
    public RawJob(String externalId, String title, String company, String location,
                  String country, String city, Boolean remote,
                  BigDecimal salaryMin, BigDecimal salaryMax, String currency,
                  String description, List<String> skills, String sourceUrl, Instant postedDate) {
        this(externalId, title, company, location, country, city, remote,
                salaryMin, salaryMax, currency, description, skills, sourceUrl, postedDate, null);
    }
}
