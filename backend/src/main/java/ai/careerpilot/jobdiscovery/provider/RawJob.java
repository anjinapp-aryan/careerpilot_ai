package ai.careerpilot.jobdiscovery.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Provider-agnostic, pre-persistence representation of a discovered job. Adapters map
 * their native payload into this; {@code JobNormalizer} fills gaps (country/city,
 * skills) and converts it into a {@code Job} entity for the global discovered pool.
 *
 * <p>{@code externalId} is the provider's stable id and, together with the source name,
 * forms the dedup key.
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
        Instant postedDate) {
}
