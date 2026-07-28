package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.repo.SupportedCountryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * International Job Discovery Engine, Phase 1 — {@link SupportedCountryService}, the read-only
 * lookup that answers "is this country part of the Phase-1 program," independent of {@code
 * InternationalScopeStrategy} (which answers "which countries can this candidate see").
 */
class SupportedCountryServiceTest {

    private final SupportedCountryRepository repository = mock(SupportedCountryRepository.class);
    private final SupportedCountryService service = new SupportedCountryService(repository, true);

    private static SupportedCountry country(String code, String name, CountryTier tier, boolean active) {
        return SupportedCountry.builder().countryCode(code).displayName(name).tier(tier).active(active).build();
    }

    @Test
    void isSupportedTrueForActiveSeededCountry() {
        when(repository.findByCountryCodeIgnoreCase("de"))
                .thenReturn(Optional.of(country("de", "Germany", CountryTier.TIER_1, true)));

        assertThat(service.isSupported("de")).isTrue();
    }

    @Test
    void isSupportedFalseForInactiveCountry() {
        when(repository.findByCountryCodeIgnoreCase("lu"))
                .thenReturn(Optional.of(country("lu", "Luxembourg", CountryTier.TIER_3, false)));

        assertThat(service.isSupported("lu")).isFalse();
    }

    @Test
    void isSupportedFalseForUnknownCountry() {
        when(repository.findByCountryCodeIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThat(service.isSupported("xx")).isFalse();
        assertThat(service.isSupported(null)).isFalse();
        assertThat(service.isSupported("")).isFalse();
    }

    @Test
    void tierOfReturnsTheSeededTier() {
        when(repository.findByCountryCodeIgnoreCase("nl"))
                .thenReturn(Optional.of(country("nl", "Netherlands", CountryTier.TIER_1, true)));

        assertThat(service.tierOf("nl")).contains(CountryTier.TIER_1);
    }

    @Test
    void byDisplayNameResolvesTheIsoCode() {
        when(repository.findByDisplayNameIgnoreCase("Germany"))
                .thenReturn(Optional.of(country("de", "Germany", CountryTier.TIER_1, true)));

        assertThat(service.byDisplayName("Germany")).isPresent();
        assertThat(service.byDisplayName("Germany").get().getCountryCode()).isEqualTo("de");
    }

    @Test
    void listActiveDelegatesToRepository() {
        when(repository.findByActiveTrue()).thenReturn(List.of(country("de", "Germany", CountryTier.TIER_1, true)));

        assertThat(service.listActive()).hasSize(1);
    }

    @Test
    void flagOffIsAByteIdenticalNoOpAcrossEveryMethod() {
        SupportedCountryService disabled = new SupportedCountryService(repository, false);
        when(repository.findByActiveTrue()).thenReturn(List.of(country("de", "Germany", CountryTier.TIER_1, true)));
        when(repository.findByCountryCodeIgnoreCase(anyString()))
                .thenReturn(Optional.of(country("de", "Germany", CountryTier.TIER_1, true)));
        when(repository.findByDisplayNameIgnoreCase(anyString()))
                .thenReturn(Optional.of(country("de", "Germany", CountryTier.TIER_1, true)));

        assertThat(disabled.listActive()).isEmpty();
        assertThat(disabled.isSupported("de")).isFalse();
        assertThat(disabled.tierOf("de")).isEmpty();
        assertThat(disabled.byDisplayName("Germany")).isEmpty();
    }
}
