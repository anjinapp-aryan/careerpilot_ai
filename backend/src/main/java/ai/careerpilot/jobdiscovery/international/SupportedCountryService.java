package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.repo.SupportedCountryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * International Job Discovery Engine, Phase 1 — read-only lookup over {@code supported_countries}.
 * Answers "is this country part of the Phase-1 program at all," a second, independent gate from
 * {@link ai.careerpilot.jobdiscovery.scope.InternationalScopeStrategy} (which answers "which
 * countries can this specific candidate see," from their own profile). No admin CRUD in Phase 1 —
 * the table is migration-seeded; a future phase can add write endpoints without changing this
 * class's read contract.
 *
 * <p>Gated by {@code career.international.tiering.enabled} (default {@code false}): every public
 * method returns the empty/false/no-match case when the flag is off, matching this codebase's
 * "master switch = byte-identical no-op when off" convention — a fresh install with the flag off
 * gets the migration-seeded table but no live behavior from it, including the read-only {@code
 * /api/jobs/international/countries}/{@code /intelligence} endpoints.
 */
@Component
public class SupportedCountryService {

    private final SupportedCountryRepository repository;
    private final boolean enabled;

    public SupportedCountryService(SupportedCountryRepository repository,
                                    @Value("${career.international.tiering.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    public List<SupportedCountry> listActive() {
        if (!enabled) return List.of();
        return repository.findByActiveTrue();
    }

    public boolean isSupported(String countryCode) {
        if (!enabled || countryCode == null || countryCode.isBlank()) return false;
        return repository.findByCountryCodeIgnoreCase(countryCode.trim())
                .map(SupportedCountry::getActive).orElse(false);
    }

    public Optional<CountryTier> tierOf(String countryCode) {
        if (!enabled || countryCode == null || countryCode.isBlank()) return Optional.empty();
        return repository.findByCountryCodeIgnoreCase(countryCode.trim()).map(SupportedCountry::getTier);
    }

    /**
     * International Job Discovery Phase 2 — the business PRIMARY/PRIMARY_SPECIALIST/SECONDARY/
     * SELECTIVE priority, independent of {@link #tierOf}. Empty for a country never assigned one
     * (e.g. UAE, deliberately outside this phase's 10-country strategy) — never a guessed default.
     */
    public Optional<SearchPriority> searchPriorityOf(String countryCode) {
        if (!enabled || countryCode == null || countryCode.isBlank()) return Optional.empty();
        return repository.findByCountryCodeIgnoreCase(countryCode.trim())
                .map(SupportedCountry::getSearchPriority).filter(java.util.Objects::nonNull);
    }

    /**
     * Resolve by the display name stored on {@link ai.careerpilot.domain.Job#getCountry()} (e.g.
     * "Germany") — the identifier space {@code jobs.country}/{@code InternationalScopeStrategy}
     * actually use, distinct from the ISO {@code country_code} this table is keyed on.
     */
    public Optional<SupportedCountry> byDisplayName(String displayName) {
        if (!enabled || displayName == null || displayName.isBlank()) return Optional.empty();
        return repository.findByDisplayNameIgnoreCase(displayName.trim());
    }
}
