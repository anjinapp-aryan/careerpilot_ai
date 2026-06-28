package ai.careerpilot.jobdiscovery.scope;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.service.CandidatePreferencesService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Domestic scope = the candidate's home country only. The home country is the single source of truth
 * (editable in {@code candidate_preferences}, snapshotted onto the profile) — the client never
 * supplies it. When no home country is set we fall back to the candidate's first preferred country
 * so existing users (who predate the home-country field) still get a populated Domestic tab; if there
 * is neither, the scope resolves to empty and the Domestic tab shows nothing rather than leaking
 * every country's jobs.
 */
@Component
public class DomesticScopeStrategy implements JobScopeStrategy {

    public static final String SCOPE = "domestic";

    private final CandidatePreferencesService preferences;

    public DomesticScopeStrategy(CandidatePreferencesService preferences) {
        this.preferences = preferences;
    }

    @Override
    public String scope() {
        return SCOPE;
    }

    @Override
    public List<String> resolveCountries(UUID userId) {
        CandidatePreferencesDto prefs = preferences.get(userId);
        String home = prefs.homeCountry();
        if (home != null && !home.isBlank()) {
            return List.of(home.trim());
        }
        List<String> preferred = prefs.preferredCountries();
        if (preferred != null && !preferred.isEmpty()) {
            return List.of(preferred.get(0).trim());
        }
        return List.of();
    }
}
