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

    private static SupportedCountry country(String code, String name, CountryTier tier, boolean active,
                                             SearchPriority priority) {
        return SupportedCountry.builder().countryCode(code).displayName(name).tier(tier).active(active)
                .searchPriority(priority).build();
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

    // ── International Job Discovery Phase 2 — search priority ──

    @Test
    void searchPriorityOfReturnsThePrimaryAssignmentForGermanyIrelandNetherlandsUk() {
        for (String code : new String[]{"de", "ie", "nl", "gb"}) {
            when(repository.findByCountryCodeIgnoreCase(code))
                    .thenReturn(Optional.of(country(code, code, CountryTier.TIER_1, true, SearchPriority.PRIMARY)));
            assertThat(service.searchPriorityOf(code)).contains(SearchPriority.PRIMARY);
        }
    }

    @Test
    void searchPriorityOfReturnsPrimarySpecialistForLuxembourg() {
        when(repository.findByCountryCodeIgnoreCase("lu"))
                .thenReturn(Optional.of(country("lu", "Luxembourg", CountryTier.TIER_3, true, SearchPriority.PRIMARY_SPECIALIST)));
        assertThat(service.searchPriorityOf("lu")).contains(SearchPriority.PRIMARY_SPECIALIST);
    }

    @Test
    void searchPriorityOfReturnsSecondaryForPolandSingaporeCanadaAustralia() {
        for (String code : new String[]{"pl", "sg", "ca", "au"}) {
            when(repository.findByCountryCodeIgnoreCase(code))
                    .thenReturn(Optional.of(country(code, code, CountryTier.TIER_2, true, SearchPriority.SECONDARY)));
            assertThat(service.searchPriorityOf(code)).contains(SearchPriority.SECONDARY);
        }
    }

    @Test
    void searchPriorityOfReturnsSelectiveForUsa() {
        when(repository.findByCountryCodeIgnoreCase("us"))
                .thenReturn(Optional.of(country("us", "United States", CountryTier.TIER_1, true, SearchPriority.SELECTIVE)));
        assertThat(service.searchPriorityOf("us")).contains(SearchPriority.SELECTIVE);
    }

    @Test
    void searchPriorityOfIsEmptyForACountryWithNoAssignment() {
        // UAE remains a supported country but is deliberately outside this 10-country priority
        // strategy — searchPriority stays NULL, never a guessed default.
        when(repository.findByCountryCodeIgnoreCase("ae"))
                .thenReturn(Optional.of(country("ae", "United Arab Emirates", CountryTier.TIER_2, true, null)));
        assertThat(service.searchPriorityOf("ae")).isEmpty();
    }

    @Test
    void searchPriorityOfIsEmptyWhenFlagOff() {
        SupportedCountryService disabled = new SupportedCountryService(repository, false);
        when(repository.findByCountryCodeIgnoreCase("de"))
                .thenReturn(Optional.of(country("de", "Germany", CountryTier.TIER_1, true, SearchPriority.PRIMARY)));
        assertThat(disabled.searchPriorityOf("de")).isEmpty();
    }
}
