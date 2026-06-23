package ai.careerpilot.jobdiscovery.provider;

import java.util.List;

/**
 * Adapter contract for a real-job source (RemoteOK, Arbeitnow, and later Adzuna/Jooble).
 *
 * <p>Mirrors the AI-gateway convention: a provider joins the active set only when
 * {@link #isConfigured()} is true. Keyless providers return {@code true} unconditionally;
 * keyed ones (Adzuna/Jooble) return {@code true} only once their API keys are present —
 * so new providers drop in with no wiring change.
 *
 * <p>{@link #fetch()} must be self-contained: it should swallow nothing the caller needs
 * to audit (let exceptions propagate so {@code JobAggregationService} can record a FAILED
 * audit row), but it must not retry aggressively or block indefinitely.
 */
public interface JobProvider {

    /** Stable lowercase source name, persisted into {@code jobs.source} (e.g. "remoteok"). */
    String name();

    /** Whether this provider is usable in the current configuration. */
    boolean isConfigured();

    /** Fetch the current page(s) of jobs from the source, mapped into {@link RawJob}. */
    List<RawJob> fetch();
}
