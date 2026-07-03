package ai.careerpilot.jobdiscovery.scope;

import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateLocationSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Domestic scope resolves entirely through {@link CandidateSignalResolver#resolveLocationSignals},
 * which itself owns the profile-vs-preferences fork (Phase 1.5, {@code single-source-enabled}).
 * This test only verifies the strategy's own fallback-to-first-preferred-country logic when no
 * home country is set — the source fork is covered by CandidateSignalResolverTest.
 */
class DomesticScopeStrategyTest {

    private final CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
    private final DomesticScopeStrategy strategy = new DomesticScopeStrategy(resolver);
    private final UUID userId = UUID.randomUUID();

    @Test
    void usesHomeCountryWhenSet() {
        when(resolver.resolveLocationSignals(userId))
                .thenReturn(new CandidateLocationSignals("India", List.of("Germany"), List.of(), "PROFILE"));

        assertEquals(List.of("India"), strategy.resolveCountries(userId));
    }

    @Test
    void fallsBackToFirstPreferredCountryWhenNoHomeCountry() {
        when(resolver.resolveLocationSignals(userId))
                .thenReturn(new CandidateLocationSignals(null, List.of("Germany", "France"), List.of(), "PREFERENCES"));

        assertEquals(List.of("Germany"), strategy.resolveCountries(userId));
    }

    @Test
    void emptyWhenNeitherHomeNorPreferredCountrySet() {
        when(resolver.resolveLocationSignals(userId))
                .thenReturn(new CandidateLocationSignals(null, List.of(), List.of(), "PREFERENCES"));

        assertTrue(strategy.resolveCountries(userId).isEmpty());
    }
}
