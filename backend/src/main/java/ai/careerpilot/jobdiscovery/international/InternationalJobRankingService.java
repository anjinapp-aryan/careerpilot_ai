package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.InternationalJobRanking;
import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateMatchSignals;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.scope.InternationalScopeStrategy;
import ai.careerpilot.jobdiscovery.scope.JobScopeStrategyResolver;
import ai.careerpilot.repo.InternationalJobRankingRepository;
import ai.careerpilot.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * International Job Discovery Engine, Phase 1 — mirrors {@code JobMatchingService.refreshForUser}'s
 * shape: resolve the candidate's Phase-1-eligible countries, pull the discovered pool for those
 * countries, filter via {@link InternationalEligibilityFilter}, score via {@link
 * InternationalJobScoring}, upsert {@code international_job_ranking}, delete stale rows. Gated by
 * {@code career.international.ranking.enabled}. Try/catch isolated at the call site (see {@code
 * JobMatchingService}) so a ranking failure never blocks the existing recommendation refresh.
 *
 * <p><b>Also depends on {@code career.international.tiering.enabled}</b>, transitively: {@link
 * SupportedCountryService#byDisplayName} (used below to resolve each candidate country into a
 * Phase-1 country row) returns empty when that flag is off, so this service writes nothing even
 * with {@code ranking.enabled=true} until both flags are on — two independent gates, same layered
 * pattern as Phase 2D's {@code <stage>.enabled} + {@code <stage>.trigger.enabled}.
 */
@Service
public class InternationalJobRankingService {

    private static final Logger log = LoggerFactory.getLogger(InternationalJobRankingService.class);
    private static final int POOL_LIMIT = 1000;

    private final CandidateSignalResolver signalResolver;
    private final JobScopeStrategyResolver scopeResolver;
    private final SupportedCountryService supportedCountries;
    private final CountryIntelligenceService countryIntelligence;
    private final InternationalEligibilityFilter eligibility;
    private final InternationalJobScoring scoring;
    private final JobRepository jobs;
    private final InternationalJobRankingRepository rankings;
    private final boolean enabled;

    public InternationalJobRankingService(CandidateSignalResolver signalResolver,
                                           JobScopeStrategyResolver scopeResolver,
                                           SupportedCountryService supportedCountries,
                                           CountryIntelligenceService countryIntelligence,
                                           InternationalEligibilityFilter eligibility,
                                           InternationalJobScoring scoring,
                                           JobRepository jobs,
                                           InternationalJobRankingRepository rankings,
                                           @Value("${career.international.ranking.enabled:false}") boolean enabled) {
        this.signalResolver = signalResolver;
        this.scopeResolver = scopeResolver;
        this.supportedCountries = supportedCountries;
        this.countryIntelligence = countryIntelligence;
        this.eligibility = eligibility;
        this.scoring = scoring;
        this.jobs = jobs;
        this.rankings = rankings;
        this.enabled = enabled;
    }

    /** Returns the number of ranking rows written. No-op (returns 0) when disabled or no eligible pool. */
    @Transactional
    public int refreshForUser(UUID userId) {
        if (!enabled) return 0;

        Optional<CandidateMatchSignals> signalsOpt = signalResolver.resolve(userId);
        if (signalsOpt.isEmpty()) return 0;
        CandidateMatchSignals signals = signalsOpt.get();

        // Candidate's own preferred countries (server-resolved, never client-supplied), narrowed to
        // the Phase-1 program's active tiered countries — a second, independent gate from the
        // candidate-preference resolution InternationalScopeStrategy already does.
        List<String> candidateCountries = scopeResolver.forScope(InternationalScopeStrategy.SCOPE).resolveCountries(userId);
        Map<String, SupportedCountry> byDisplayNameLower = new HashMap<>();
        for (String c : candidateCountries) {
            supportedCountries.byDisplayName(c).ifPresent(sc -> byDisplayNameLower.put(c.toLowerCase(), sc));
        }
        if (byDisplayNameLower.isEmpty()) return 0;
        List<String> countriesLower = byDisplayNameLower.keySet().stream().toList();

        List<Job> pool = jobs.findDiscoveredInCountries(countriesLower, null, null, null, null,
                PageRequest.of(0, POOL_LIMIT)).getContent();
        if (pool.isEmpty()) return 0;

        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                signals.skills(), signals.targetRole(), signals.targetLocations(),
                signals.yearsExperience(), signals.atsScore());

        Map<UUID, InternationalJobRanking> existing = new HashMap<>();
        for (InternationalJobRanking r : rankings.findByUserIdOrderByRankScoreDesc(userId)) {
            existing.put(r.getJobId(), r);
        }
        Set<UUID> keptJobIds = new HashSet<>();

        int written = 0;
        for (Job job : pool) {
            if (!eligibility.isEligible(job)) continue;

            SupportedCountry sc = job.getCountry() == null ? null
                    : byDisplayNameLower.get(job.getCountry().toLowerCase());
            if (sc == null) continue; // job's country isn't one of this candidate's Phase-1 countries

            CountryIntelligence intel = countryIntelligence.forCountry(sc.getCountryCode()).orElse(null);
            InternationalJobScoring.InternationalScoreResult r =
                    scoring.score(job, job.getSkills(), ctx, signals.preferences(), intel);

            InternationalJobRanking row = existing.getOrDefault(job.getId(),
                    InternationalJobRanking.builder().userId(userId).jobId(job.getId()).build());
            row.setRankScore(r.rankScore());
            row.setSkillScore(r.skillScore());
            row.setVisaProbabilityScore(r.visaProbabilityScore());
            row.setSalaryScore(r.salaryScore());
            row.setCareerGrowthScore(r.careerGrowthScore());
            row.setCompanyStabilityScore(r.companyStabilityScore());
            row.setRemoteFlexibilityScore(r.remoteFlexibilityScore());
            row.setPrincipalEngineerGrowthScore(r.principalEngineerGrowthScore());
            row.setAiTechStackScore(r.aiTechStackScore());
            row.setCountryCode(sc.getCountryCode());
            row.setTier(sc.getTier().name());
            rankings.save(row);
            keptJobIds.add(job.getId());
            written++;
        }

        List<InternationalJobRanking> stale = existing.values().stream()
                .filter(r -> !keptJobIds.contains(r.getJobId()))
                .toList();
        if (!stale.isEmpty()) rankings.deleteAll(stale);

        log.debug("International ranking refreshed {} rows ({} removed) for user {}", written, stale.size(), userId);
        return written;
    }
}
