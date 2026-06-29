package ai.careerpilot.jobdiscovery.scope;

import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Domestic scope = the candidate's home country only. The home country is the single source of truth
 * (editable in {@code candidate_preferences}, snapshotted onto the profile) — the client never
 * supplies it. Resolved via {@link CandidateSignalResolver#resolveLocationSignals}, which reads the
 * canonical {@code CandidateProfile} when {@code candidate.profile.single-source-enabled} is on and a
 * profile row exists, falling back to live {@code candidate_preferences} otherwise — byte-for-byte
 * the behavior that shipped before Phase 1.5. When no home country is set we fall back to the
 * candidate's first preferred country so existing users (who predate the home-country field) still get
 * a populated Domestic tab; if there is neither, the scope resolves to empty and the Domestic tab shows
 * nothing rather than leaking every country's jobs.
 */
@Component
public class DomesticScopeStrategy implements JobScopeStrategy {

    public static final String SCOPE = "domestic";

    private final CandidateSignalResolver signalResolver;

    public DomesticScopeStrategy(CandidateSignalResolver signalResolver) {
        this.signalResolver = signalResolver;
    }

    @Override
    public String scope() {
        return SCOPE;
    }

    @Override
    public List<String> resolveCountries(UUID userId) {
        CandidateSignalResolver.CandidateLocationSignals signals = signalResolver.resolveLocationSignals(userId);
        String home = signals.homeCountry();
        if (home != null && !home.isBlank()) {
            return List.of(home.trim());
        }
        List<String> preferred = signals.preferredCountries();
        if (preferred != null && !preferred.isEmpty()) {
            return List.of(preferred.get(0).trim());
        }
        return List.of();
    }
}
