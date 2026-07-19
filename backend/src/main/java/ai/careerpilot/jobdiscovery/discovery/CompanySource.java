package ai.careerpilot.jobdiscovery.discovery;

import java.util.Optional;

/**
 * Gap A — Company Discovery Agent. One keyless-public-ATS "does this company slug exist here"
 * probe. Deliberately NOT an extension of {@link ai.careerpilot.jobdiscovery.provider.JobProvider}
 * — that interface's {@code fetch()} returns a list of job postings for already-known,
 * already-configured companies; a {@code CompanySource} instead answers a per-candidate-slug
 * existence question and returns connector metadata, not job listings. Different shape, different
 * concern — see the Gap A task notes for why these are not unified.
 *
 * <p>Implementations must never throw out of {@link #probe}: any HTTP failure, non-200 response,
 * or malformed body is swallowed and surfaced as {@link Optional#empty()}. Implementations must
 * never retry aggressively (one attempt, no backoff loop) and must never fabricate a positive
 * result — a hit is reported only when the source's real endpoint responds with a well-formed,
 * non-empty body for that exact candidate slug.
 */
public interface CompanySource {

    /** Short lowercase identifier, e.g. {@code "greenhouse"}, {@code "workday"}. */
    String name();

    /** Whether this source is enabled/configured and should be probed at all. */
    boolean isConfigured();

    /**
     * Probe one candidate company slug (e.g. a Greenhouse board token, an Ashby org slug, a
     * Workday tenant guess) against this source's real public endpoint. Returns a populated
     * {@link DiscoveredCandidate} only on a genuine, well-formed hit; {@link Optional#empty()}
     * otherwise (including on any error).
     */
    Optional<DiscoveredCandidate> probe(String candidateSlug);
}
