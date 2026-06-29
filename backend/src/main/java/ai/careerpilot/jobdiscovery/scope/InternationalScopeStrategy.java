package ai.careerpilot.jobdiscovery.scope;

import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * International scope = the candidate's preferred countries, excluding the home country (which the
 * Domestic tab owns). This is a strict allow-list: unknown-country or non-preferred jobs do NOT
 * appear here — they surface under Browse instead. Resolved via
 * {@link CandidateSignalResolver#resolveLocationSignals}, which reads the canonical
 * {@code CandidateProfile} when {@code candidate.profile.single-source-enabled} is on and a profile
 * row exists, falling back to live {@code candidate_preferences} otherwise — byte-for-byte the
 * behavior that shipped before Phase 1.5. When the candidate has no preferred countries the scope
 * resolves to empty and the International tab shows nothing rather than the whole global pool.
 */
@Component
public class InternationalScopeStrategy implements JobScopeStrategy {

    public static final String SCOPE = "international";

    private final CandidateSignalResolver signalResolver;

    public InternationalScopeStrategy(CandidateSignalResolver signalResolver) {
        this.signalResolver = signalResolver;
    }

    @Override
    public String scope() {
        return SCOPE;
    }

    @Override
    public List<String> resolveCountries(UUID userId) {
        CandidateSignalResolver.CandidateLocationSignals signals = signalResolver.resolveLocationSignals(userId);
        List<String> preferred = signals.preferredCountries();
        if (preferred == null || preferred.isEmpty()) return List.of();

        String home = signals.homeCountry();
        String homeNorm = home == null ? null : home.trim();
        return preferred.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .filter(c -> homeNorm == null || !c.equalsIgnoreCase(homeNorm))
                .distinct()
                .toList();
    }
}
