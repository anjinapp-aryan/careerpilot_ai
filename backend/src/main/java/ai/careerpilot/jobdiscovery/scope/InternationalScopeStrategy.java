package ai.careerpilot.jobdiscovery.scope;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.service.CandidatePreferencesService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * International scope = the candidate's preferred countries, excluding the home country (which the
 * Domestic tab owns). This is a strict allow-list: unknown-country or non-preferred jobs do NOT
 * appear here — they surface under Browse instead. When the candidate has no preferred countries the
 * scope resolves to empty and the International tab shows nothing rather than the whole global pool.
 */
@Component
public class InternationalScopeStrategy implements JobScopeStrategy {

    public static final String SCOPE = "international";

    private final CandidatePreferencesService preferences;

    public InternationalScopeStrategy(CandidatePreferencesService preferences) {
        this.preferences = preferences;
    }

    @Override
    public String scope() {
        return SCOPE;
    }

    @Override
    public List<String> resolveCountries(UUID userId) {
        CandidatePreferencesDto prefs = preferences.get(userId);
        List<String> preferred = prefs.preferredCountries();
        if (preferred == null || preferred.isEmpty()) return List.of();

        String home = prefs.homeCountry();
        String homeNorm = home == null ? null : home.trim();
        return preferred.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .filter(c -> homeNorm == null || !c.equalsIgnoreCase(homeNorm))
                .distinct()
                .toList();
    }
}
