package ai.careerpilot.jobdiscovery.scope;

import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateLocationSignals;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * International scope resolves entirely through
 * {@link CandidateSignalResolver#resolveLocationSignals} — this test only verifies the strategy's
 * own home-country-exclusion logic; the profile-vs-preferences source fork is covered by
 * CandidateSignalResolverTest.
 */
class InternationalScopeStrategyTest {

    private final CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
    private final InternationalScopeStrategy strategy = new InternationalScopeStrategy(resolver);
    private final UUID userId = UUID.randomUUID();

    @Test
    void excludesHomeCountryFromPreferredList() {
        when(resolver.resolveLocationSignals(userId))
                .thenReturn(new CandidateLocationSignals("Germany", List.of("Germany", "France"), List.of(), "PROFILE"));

        assertEquals(List.of("France"), strategy.resolveCountries(userId));
    }

    @Test
    void emptyWhenNoPreferredCountries() {
        when(resolver.resolveLocationSignals(userId))
                .thenReturn(new CandidateLocationSignals("Germany", List.of(), List.of(), "PREFERENCES"));

        assertTrue(strategy.resolveCountries(userId).isEmpty());
    }
}
