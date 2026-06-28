package ai.careerpilot.jobdiscovery.scope;

import java.util.List;
import java.util.UUID;

/**
 * Resolves which countries a discovery tab ("scope") should show for a given candidate. This is the
 * Strategy seam that keeps the Domestic/International separation free of {@code if (tab == ...)}
 * branching: each scope is a strategy that maps a user to a country set, and the discovery read is
 * a single country-IN query over that set.
 *
 * <p>Crucially, the country set is derived <b>server-side from the candidate's own profile</b>
 * (home country / preferred countries) — never from a client-supplied query param — so the Domestic
 * tab can only ever return the candidate's home-country jobs regardless of what the UI sends.
 */
public interface JobScopeStrategy {

    /** Scope id this strategy serves, e.g. {@code "domestic"} or {@code "international"}. */
    String scope();

    /**
     * Countries to include for this scope and user. An empty list means "this candidate has no
     * countries configured for this scope" — the caller returns an empty page (it never falls back
     * to an unscoped global read, which would defeat the separation).
     */
    List<String> resolveCountries(UUID userId);
}
