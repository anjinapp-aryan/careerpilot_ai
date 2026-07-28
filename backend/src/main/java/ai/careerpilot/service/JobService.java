package ai.careerpilot.service;

import ai.careerpilot.discovery.relevance.CareerRelevanceEvaluator;
import ai.careerpilot.discovery.relevance.CareerRelevanceScore;
import ai.careerpilot.discovery.relevance.CareerThresholdPolicy;
import ai.careerpilot.discovery.relevance.RelevanceCandidateContext;
import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.RoleExclusionFilter;
import ai.careerpilot.jobdiscovery.international.InternationalEligibilityFilter;
import ai.careerpilot.jobdiscovery.scope.JobScopeStrategyResolver;
import ai.careerpilot.repo.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobs;
    private final int recommendThreshold;

    private final JobScopeStrategyResolver scopeResolver;
    private final RoleExclusionFilter roleExclusion;
    private final CandidateSignalResolver signalResolver;
    private final CareerRelevanceEvaluator relevanceEvaluator;
    private final CareerThresholdPolicy thresholdPolicy;
    private final InternationalEligibilityFilter internationalEligibility;
    /**
     * When true, Domestic/International are derived strictly from the candidate's own profile
     * (home country / preferred countries) and excluded roles are filtered out. When false, the
     * legacy client-country behavior is used verbatim — instant rollback, zero regression.
     */
    private final boolean scopeStrictEnabled;
    /**
     * Phase 3B.1 — additive career-relevance feed filter. Both default false; when on (and the
     * {@link CareerRelevanceEvaluator} master flag {@code career.relevance.enabled} is also on),
     * Domestic/International additionally require {@code relevanceScore >= 70} and sort by
     * relevance desc, postedDate desc. Independent flags so Domestic and International can be
     * canaried separately.
     */
    private final boolean domesticRelevanceFilterEnabled;
    private final boolean internationalRelevanceFilterEnabled;

    public JobService(JobRepository jobs,
                      JobScopeStrategyResolver scopeResolver,
                      RoleExclusionFilter roleExclusion,
                      CandidateSignalResolver signalResolver,
                      CareerRelevanceEvaluator relevanceEvaluator,
                      CareerThresholdPolicy thresholdPolicy,
                      InternationalEligibilityFilter internationalEligibility,
                      @Value("${jobs.recommendation.threshold:75}") int recommendThreshold,
                      @Value("${jobs.discovery.scope-strict-enabled:false}") boolean scopeStrictEnabled,
                      @Value("${career.domestic.filter.enabled:false}") boolean domesticRelevanceFilterEnabled,
                      @Value("${career.international.filter.enabled:false}") boolean internationalRelevanceFilterEnabled) {
        this.jobs = jobs;
        this.scopeResolver = scopeResolver;
        this.roleExclusion = roleExclusion;
        this.signalResolver = signalResolver;
        this.relevanceEvaluator = relevanceEvaluator;
        this.thresholdPolicy = thresholdPolicy;
        this.internationalEligibility = internationalEligibility;
        this.recommendThreshold = recommendThreshold;
        this.scopeStrictEnabled = scopeStrictEnabled;
        this.domesticRelevanceFilterEnabled = domesticRelevanceFilterEnabled;
        this.internationalRelevanceFilterEnabled = internationalRelevanceFilterEnabled;
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

        String scopeNorm = "domestic".equalsIgnoreCase(scope) ? "domestic" : "international";

        if (scopeStrictEnabled) {
            // Country set comes from the candidate, never the client param.
            List<String> countries = scopeResolver.forScope(scope).resolveCountries(userId).stream()
                    .map(s -> s.toLowerCase()).distinct().toList();
            if (countries.isEmpty()) return Page.empty(pageable);

            Page<Job> result = jobs.findDiscoveredInCountries(countries,
                    blankToNull(remoteType), sponsorship, relocation, blankToNull(q), pageable);
            result = applyRoleExclusion(userId, result, pageable);
            result = applyInternationalEligibility(scopeNorm, result, pageable);
            return applyCareerRelevance(userId, scopeNorm, result, pageable);
        }

        // ── Legacy path (flag off): client-supplied country, no exclusion filter ──
        String c = (country == null || country.isBlank()) ? "India" : country.trim();
        Page<Job> result = jobs.findDiscoveredFiltered(scopeNorm, c,
                blankToNull(remoteType), sponsorship, relocation, blankToNull(q), pageable);
        return applyCareerRelevance(userId, scopeNorm, result, pageable);
    }

    /**
     * Phase 3B.1 — Steps 7/8: additive career-relevance gate + sort for Domestic/International.
     * No-op (returns {@code page} unchanged) unless BOTH the master flag
     * ({@code career.relevance.enabled}) and the scope-specific flag are on — Browse
     * ({@link #browsePool}) never calls this, so it remains the unfiltered "everything" tab.
     *
     * <p>Filtering happens on the already-paginated DB page (same pattern as
     * {@link #applyRoleExclusion}), so a page can come back with fewer than {@code size} results
     * once the flag is on — a known, documented tradeoff (see docs/PHASE_3B_1_FILTER_RULES.md)
     * rather than a pool-then-paginate rewrite, to keep this change minimal and additive.
     */
    private Page<Job> applyCareerRelevance(UUID userId, String scopeNorm, Page<Job> page, Pageable pageable) {
        boolean scopeFilterEnabled = "domestic".equals(scopeNorm)
                ? domesticRelevanceFilterEnabled : internationalRelevanceFilterEnabled;
        if (!relevanceEvaluator.isEnabled() || !scopeFilterEnabled || page.getContent().isEmpty()) {
            return page;
        }

        RelevanceCandidateContext ctx = relevanceEvaluator.resolveContext(userId);
        record Scored(Job job, CareerRelevanceScore score) {}
        List<Scored> scored = page.getContent().stream()
                .map(j -> new Scored(j, relevanceEvaluator.score(j, ctx)))
                .filter(s -> thresholdPolicy.isVisibleForScope(scopeNorm, s.score().relevanceScore()))
                .sorted(Comparator
                        .comparingInt((Scored s) -> s.score().relevanceScore()).reversed()
                        .thenComparing((Scored s) -> s.job().getPostedDate(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Job> kept = scored.stream().map(Scored::job).toList();
        long total = page.getTotalElements() - (page.getContent().size() - kept.size());
        return new PageImpl<>(kept, pageable, total);
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

    /**
     * International Job Discovery Engine, Phase 1 — scope-aware post-filter: only applied when
     * {@code scopeNorm} is "international" (Domestic and Browse are untouched). No-op when the
     * underlying {@code career.international.seniority-filter.enabled} flag is off, since {@link
     * InternationalEligibilityFilter#isEligible} itself returns {@code true} unconditionally in
     * that case. Same already-paginated-page filtering tradeoff as {@link #applyRoleExclusion}.
     */
    private Page<Job> applyInternationalEligibility(String scopeNorm, Page<Job> page, Pageable pageable) {
        if (!"international".equals(scopeNorm) || page.getContent().isEmpty()) return page;
        List<Job> kept = page.getContent().stream()
                .filter(internationalEligibility::isEligible)
                .toList();
        if (kept.size() == page.getContent().size()) return page;
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

    /**
     * Batch job lookup by ID, unscoped by org. Safe because callers (Saved/Applied) already
     * restrict {@code ids} to jobs the current user has an {@code Application} row for — and
     * {@code Application} ownership is enforced by {@code user_id} elsewhere — so this never
     * exposes a job the caller couldn't already see. Needed because discovered-pool jobs have
     * {@code org_id IS NULL} and can never match an org-scoped lookup like {@link #get}.
     */
    public List<Job> getByIds(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jobs.findAllById(ids);
    }
}
