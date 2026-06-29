package ai.careerpilot.service;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.RoleExclusionFilter;
import ai.careerpilot.jobdiscovery.scope.JobScopeStrategyResolver;
import ai.careerpilot.repo.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobs;
    private final int recommendThreshold;

    private final JobScopeStrategyResolver scopeResolver;
    private final RoleExclusionFilter roleExclusion;
    private final CandidateSignalResolver signalResolver;
    /**
     * When true, Domestic/International are derived strictly from the candidate's own profile
     * (home country / preferred countries) and excluded roles are filtered out. When false, the
     * legacy client-country behavior is used verbatim — instant rollback, zero regression.
     */
    private final boolean scopeStrictEnabled;

    public JobService(JobRepository jobs,
                      JobScopeStrategyResolver scopeResolver,
                      RoleExclusionFilter roleExclusion,
                      CandidateSignalResolver signalResolver,
                      @Value("${jobs.recommendation.threshold:75}") int recommendThreshold,
                      @Value("${jobs.discovery.scope-strict-enabled:false}") boolean scopeStrictEnabled) {
        this.jobs = jobs;
        this.scopeResolver = scopeResolver;
        this.roleExclusion = roleExclusion;
        this.signalResolver = signalResolver;
        this.recommendThreshold = recommendThreshold;
        this.scopeStrictEnabled = scopeStrictEnabled;
    }

    public Page<Job> search(UUID orgId, String q, int page, int size) {
        return jobs.search(orgId, q, PageRequest.of(page, Math.min(size, 100)));
    }

    /**
     * Read the global discovered-job pool (org_id IS NULL) for the Domestic/International tabs.
     *
     * <p>When {@code jobs.discovery.scope-strict-enabled} is on, the country set is resolved
     * server-side from the candidate's own profile via a {@link JobScopeStrategyResolver} — the
     * client-supplied {@code country} is ignored, so Domestic can only ever return the candidate's
     * home-country jobs — and user-excluded roles are filtered out of the result. When off, the
     * legacy client-country filter is used unchanged.
     */
    public Page<Job> discovered(UUID userId, String scope, String country, String remoteType,
                                Boolean sponsorship, Boolean relocation, String q,
                                int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100));

        if (scopeStrictEnabled) {
            // Country set comes from the candidate, never the client param.
            List<String> countries = scopeResolver.forScope(scope).resolveCountries(userId).stream()
                    .map(s -> s.toLowerCase()).distinct().toList();
            if (countries.isEmpty()) return Page.empty(pageable);

            Page<Job> result = jobs.findDiscoveredInCountries(countries,
                    blankToNull(remoteType), sponsorship, relocation, blankToNull(q), pageable);
            return applyRoleExclusion(userId, result, pageable);
        }

        // ── Legacy path (flag off): client-supplied country, no exclusion filter ──
        String c = (country == null || country.isBlank()) ? "India" : country.trim();
        String scopeNorm = "domestic".equalsIgnoreCase(scope) ? "domestic" : "international";
        return jobs.findDiscoveredFiltered(scopeNorm, c,
                blankToNull(remoteType), sponsorship, relocation, blankToNull(q), pageable);
    }

    /**
     * Drop user-excluded roles from a discovered page (shared logic with the recommendation
     * matcher). Excluded roles come from {@link CandidateSignalResolver#resolveLocationSignals},
     * which reads the canonical profile when {@code candidate.profile.single-source-enabled} is on
     * and a profile row exists, falling back to live {@code candidate_preferences} otherwise.
     */
    private Page<Job> applyRoleExclusion(UUID userId, Page<Job> page, Pageable pageable) {
        List<String> excluded = signalResolver.resolveLocationSignals(userId).excludedRoles();
        if (excluded.isEmpty()) return page;
        List<Job> kept = page.getContent().stream()
                .filter(j -> !roleExclusion.isExcluded(j, excluded))
                .toList();
        if (kept.size() == page.getContent().size()) return page;
        // Total is adjusted by the number filtered from this page so paging stays monotonic.
        long total = page.getTotalElements() - (page.getContent().size() - kept.size());
        return new PageImpl<>(kept, pageable, total);
    }

    /** Browse "more opportunities": discovered jobs that didn't clear this user's Recommended bar. */
    public Page<Job> browsePool(UUID userId, int page, int size) {
        return jobs.findBrowsePool(userId, recommendThreshold, PageRequest.of(page, Math.min(size, 100)));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    @Transactional
    public Job create(UUID orgId, Job j) {
        j.setOrgId(orgId);
        return jobs.save(j);
    }

    public Job get(UUID orgId, UUID id) {
        return jobs.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found or access denied"));
    }
}
