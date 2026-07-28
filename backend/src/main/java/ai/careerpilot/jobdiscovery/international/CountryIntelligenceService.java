package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.repo.CountryIntelligenceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * International Job Discovery Engine, Phase 1 — thin lookup over the curated
 * {@code country_intelligence} reference table. No Redis caching in Phase 1 (six rows, cheap to
 * hit directly); {@link ai.careerpilot.jobdiscovery.cache.MatchCache}'s pattern is the seam to
 * reuse later if load testing shows it's needed.
 */
@Component
public class CountryIntelligenceService {

    private final CountryIntelligenceRepository repository;

    public CountryIntelligenceService(CountryIntelligenceRepository repository) {
        this.repository = repository;
    }

    public Optional<CountryIntelligence> forCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return Optional.empty();
        return repository.findByCountryCodeIgnoreCase(countryCode.trim());
    }
}
