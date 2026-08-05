package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobAiEnrichment;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.RecommendationAudit;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateMatchSignals;
import ai.careerpilot.jobdiscovery.cache.MatchCache;
import ai.careerpilot.jobdiscovery.international.InternationalEligibilityFilter;
import ai.careerpilot.learning.recommendation.LearningRecommendationBooster;
import ai.careerpilot.repo.CandidateProfileVersionRepository;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import ai.careerpilot.service.profile.JsonLists;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Rule-based matcher (no LLM). Scores the global discovered-job pool against a user's
 * canonical {@link ai.careerpilot.domain.CandidateProfile} (resolved by
 * {@link CandidateSignalResolver}) using the shared {@link JobScoring}, then upserts the top
 * results into {@code job_recommendations} so the Recommended tab is a cheap indexed read.
 *
 * <p>Where the candidate signals come from (profile vs. legacy workflow snapshot) is owned by
 * the resolver and switched by {@code jobs.matching.profile-source-enabled}; this service is
 * source-agnostic and only scores/filters/persists.
 */
@Service
public class JobMatchingService {

    private static final Logger log = LoggerFactory.getLogger(JobMatchingService.class);

    // Scored entirely in-memory (rule-based, no LLM), so this is cheap. Kept well above the current
    // discovered-pool size so every listing is considered — a 200-cap ordered by posted_date let the
    // freshest provider (German Arbeitnow) crowd out older, highly-relevant RemoteOK roles entirely.
    private static final int POOL_LIMIT = 1000;  // discovered jobs scored per refresh
    private static final int KEEP_TOP = 50;      // recommendations persisted per user

    private final CandidateSignalResolver signalResolver;
    private final JobRepository jobs;
    private final JobRecommendationRepository recommendations;
    private final JobScoring scoring;
    private final JobTaxonomy taxonomy;
    private final RoleExclusionFilter roleExclusion;
    private final CandidateProfileVersionRepository profileVersions;
    private final RecommendationAuditRepository recommendationAudit;
    private final JobAiEnrichmentRepository enrichment;
    private final JobCategorizer categorizer;
    private final PreferenceGate preferenceGate;
    private final MatchCache matchCache;
    private final ai.careerpilot.jobdiscovery.priority.PriorityEngine priorityEngine;
    private final MustApplyEvaluator mustApplyEvaluator;
    private final LearningRecommendationBooster learningBooster;
    private final ai.careerpilot.companyintel.CompanyKnowledgeBooster companyBooster;
    private final ai.careerpilot.memory.CareerMemoryBooster careerMemoryBooster;
    private final ai.careerpilot.intelligence.ProductionIntelligenceBooster productionBooster;
    private final ai.careerpilot.intelligence.ProductionIntelligenceService productionIntelligence;
    private final InternationalEligibilityFilter internationalEligibility;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Full-spec Recommended gate: score >= minScore AND >= 1 role family AND >= 3 skill families. */
    private final boolean strictGateEnabled;
    private final int gateMinScore;
    private final int gateMinSkillFamilies;
    /** Exclude non-technical job families (Marketing/Sales/HR/…) from a tech candidate's matches. */
    private final boolean industryFilterEnabled;
    /**
     * Phase 1.5 — persist the per-job scoring breakdown into {@code recommendation_audit} for
     * future explainability. Purely additive after scoring; never affects the returned
     * recommendation. Default off.
     */
    private final boolean recommendationAuditEnabled;
    /**
     * When true, the matcher scores against the AI-enriched {@code normalized_skills} from
     * {@code job_ai_enrichment} (when a job has an enrichment row) instead of the raw, often-thin
     * provider {@code jobs.skills} column. This lets prose-only listings (where the skills column is
     * a generic placeholder) match a candidate on their real tech stack. Falls back to the raw
     * skills for any job without enrichment. Default off; instant rollback.
     */
    private final boolean useEnrichment;
    /**
     * Pre-gate: reject a discovered job before full scoring if its role similarity to the candidate
     * is below this threshold (0–100). Default 10 — low enough that enrichment or description text
     * can clear it, high enough to still block completely off-domain listings. The strict
     * 3-factor gate (score + roleCount + skillFamilies) is the real filter; this is only a
     * cheap early exit to avoid scoring the most obviously irrelevant jobs.
     */
    private final int roleRelevanceMin;
    /**
     * Phase 2B-5 (Step 6) — reject a job outright before scoring when it structurally violates a
     * candidate's mandatory work-mode/location/visa preference (e.g. "Remote only" rejects an
     * onsite job even at 100% skill match). Default off: today those preferences are soft weights
     * only. See {@link PreferenceGate} for exactly which signals trigger a reject.
     */
    private final boolean hardPreferenceEnabled;

    public JobMatchingService(CandidateSignalResolver signalResolver,
                              JobRepository jobs,
                              JobRecommendationRepository recommendations,
                              JobScoring scoring,
                              JobTaxonomy taxonomy,
                              RoleExclusionFilter roleExclusion,
                              CandidateProfileVersionRepository profileVersions,
                              RecommendationAuditRepository recommendationAudit,
                              JobAiEnrichmentRepository enrichment,
                              JobCategorizer categorizer,
                              PreferenceGate preferenceGate,
                              MatchCache matchCache,
                              ai.careerpilot.jobdiscovery.priority.PriorityEngine priorityEngine,
                              MustApplyEvaluator mustApplyEvaluator,
                              LearningRecommendationBooster learningBooster,
                              ai.careerpilot.companyintel.CompanyKnowledgeBooster companyBooster,
                              ai.careerpilot.memory.CareerMemoryBooster careerMemoryBooster,
                              ai.careerpilot.intelligence.ProductionIntelligenceBooster productionBooster,
                              ai.careerpilot.intelligence.ProductionIntelligenceService productionIntelligence,
                              InternationalEligibilityFilter internationalEligibility,
                              @Value("${jobs.recommendation.strict-gate-enabled:true}") boolean strictGateEnabled,
                              @Value("${jobs.recommendation.gate-min-score:70}") int gateMinScore,
                              @Value("${jobs.recommendation.gate-min-skills:3}") int gateMinSkillFamilies,
                              @Value("${jobs.industry.filter-enabled:true}") boolean industryFilterEnabled,
                              @Value("${candidate.recommendation.audit-enabled:false}") boolean recommendationAuditEnabled,
                              @Value("${jobs.matching.use-enrichment:false}") boolean useEnrichment,
                              @Value("${jobs.matching.role-relevance-min:10}") int roleRelevanceMin,
                              @Value("${jobs.matching.hard-preference-enabled:false}") boolean hardPreferenceEnabled) {
        this.signalResolver = signalResolver;
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.scoring = scoring;
        this.taxonomy = taxonomy;
        this.roleExclusion = roleExclusion;
        this.profileVersions = profileVersions;
        this.recommendationAudit = recommendationAudit;
        this.enrichment = enrichment;
        this.categorizer = categorizer;
        this.preferenceGate = preferenceGate;
        this.matchCache = matchCache;
        this.priorityEngine = priorityEngine;
        this.mustApplyEvaluator = mustApplyEvaluator;
        this.learningBooster = learningBooster;
        this.companyBooster = companyBooster;
        this.careerMemoryBooster = careerMemoryBooster;
        this.productionBooster = productionBooster;
        this.productionIntelligence = productionIntelligence;
        this.internationalEligibility = internationalEligibility;
        this.strictGateEnabled = strictGateEnabled;
        this.gateMinScore = gateMinScore;
        this.gateMinSkillFamilies = gateMinSkillFamilies;
        this.industryFilterEnabled = industryFilterEnabled;
        this.recommendationAuditEnabled = recommendationAuditEnabled;
        this.useEnrichment = useEnrichment;
        this.roleRelevanceMin = roleRelevanceMin;
        this.hardPreferenceEnabled = hardPreferenceEnabled;
    }

    /**
     * Recompute and persist recommendations for a user against the current discovered pool.
     * Returns the number of recommendations written. No-op (returns 0) when the user has no
     * workflow snapshot or the discovered pool is empty.
     */
    @Transactional
    public int refreshForUser(UUID userId) {
        CandidateMatchSignals signals = signalResolver.resolve(userId).orElse(null);
        if (signals == null) return 0;   // no profile and no workflow run → nothing to score against

        // Phase 2B-3: skip the rescore + persist cycle entirely when nothing has changed (same
        // resume/profile version AND same discovered-pool version) since the last refresh, within the
        // cache's 24h TTL. job_recommendations already reflects this exact state, so 0 is written —
        // "written" honestly means "changed", not "exists". No-op (always a miss) when disabled.
        java.time.Instant poolVersion = matchCache.isEnabled() ? jobs.maxDiscoveredCreatedAt() : null;
        if (matchCache.isFresh(userId, signals.resumeId(), poolVersion)) {
            return 0;
        }

        List<Job> pool = jobs.findDiscoveredPool(POOL_LIMIT);
        if (pool.isEmpty()) return 0;

        List<String> skills = signals.skills();
        List<String> targetLocations = signals.targetLocations();
        UUID resumeId = signals.resumeId();
        JobScoring.PreferenceContext prefs = signals.preferences();
        String targetRole = signals.targetRole();
        List<String> excludedRoles = signals.excludedRoles();

        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                skills, targetRole, targetLocations, signals.yearsExperience(), signals.atsScore());

        // Industry filter: the candidate's own family (TECH for an engineer) is never excluded, so a
        // candidate who actually targets Sales/Marketing still sees those roles.
        String candidateFamily = taxonomy.classifyFamily(targetRole, null);

        // AI-enriched skill signal per job (one query for the whole pool), used in place of the raw,
        // often-generic provider skills column when jobs.matching.use-enrichment is on. Empty map when
        // the flag is off or no pool job is enriched → every effectiveSkills() falls back to the raw.
        Map<UUID, String> enrichedSkills = loadEnrichedSkills(pool);

        // 1) Quality filtering: drop user-excluded roles (hard filter), then non-technical families
        //    (industry filter), then clearly role-irrelevant jobs (relevance pre-gate). 2) Score what
        //    survives. 3) Apply the full Recommended gate (score + role + skills). 4) Rank, keep top N.
        //    Jobs that fail any gate are simply not persisted, so they surface under Browse instead.
        record Scored(Job job, JobScoring.ScoreResultV2 result) {}
        // TEMPORARY diagnostic counters (Phase: 2026-07-28 zero-match investigation) — per-stage
        // attrition so we can see WHERE a pool gets filtered to zero instead of guessing. Revert
        // once root-caused.
        java.util.concurrent.atomic.AtomicInteger cRoleExcl = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cIndustryExcl = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cHardPref = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cRoleSim = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cGate = new java.util.concurrent.atomic.AtomicInteger();
        // Phase 13C — resolved ONCE for the whole pool, never inside the stream. A per-job lookup
        // here would be four repository reads multiplied by the pool size; this way the production
        // -evidence adjustment costs the same whether the pool holds ten jobs or a thousand.
        // Null when the booster is dark, so no snapshot is even built on a stock deployment.
        final ai.careerpilot.intelligence.ProductionOptimizationSnapshot productionSnapshot =
                productionBooster.isActive() ? safeProductionSnapshot(userId) : null;

        List<Scored> scored = pool.stream()
                .filter(j -> { boolean ok = !isRoleExcluded(j, excludedRoles); if (!ok) cRoleExcl.incrementAndGet(); return ok; })
                .filter(j -> { boolean ok = !isIndustryExcluded(j, candidateFamily); if (!ok) cIndustryExcl.incrementAndGet(); return ok; })
                .filter(internationalEligibility::isEligible)
                .filter(j -> { boolean ok = !hardPreferenceEnabled || !preferenceGate.isHardRejected(j, prefs); if (!ok) cHardPref.incrementAndGet(); return ok; })
                .filter(j -> {
                    int rs = scoring.roleSimilarity(j, effectiveSkills(j, enrichedSkills), skills, targetRole);
                    boolean ok = rs < 0 || rs >= roleRelevanceMin;
                    if (!ok) cRoleSim.incrementAndGet();
                    return ok;
                })
                .map(j -> new Scored(j, applyProductionIntelligenceBoost(productionSnapshot, j,
                        applyCareerMemoryBoost(userId, j, applyCompanyKnowledgeBoost(userId, j,
                        applyLearningBoost(userId, j, scoring.scoreV2(j, effectiveSkills(j, enrichedSkills), ctx, prefs)))))))
                .filter(s -> { boolean ok = passesGate(s.result()); if (!ok) cGate.incrementAndGet(); return ok; })
                .toList();
        log.info("RECO_MATCH_DEBUG pool={} droppedRoleExcl={} droppedIndustry={} droppedHardPref={} droppedRoleSim={} droppedGate={} survived={}",
                pool.size(), cRoleExcl.get(), cIndustryExcl.get(), cHardPref.get(), cRoleSim.get(), cGate.get(), scored.size());
        List<Scored> ranked = scored.stream()
                .sorted(Comparator.comparingInt((Scored s) -> s.result().matchScore()).reversed())
                .limit(KEEP_TOP)
                .toList();
        log.info("RECO_MATCH user={} source={} pool={} gated={} persisted={} strictGate={} industryFilter={} hardPreference={} excluded={}",
                userId, signals.source(), pool.size(), scored.size(), ranked.size(),
                strictGateEnabled, industryFilterEnabled, hardPreferenceEnabled, excludedRoles.size());

        // Batch-load this user's existing recommendation rows once (was an N+1 SELECT per job),
        // and remove rows that no longer qualify so the Recommended tab can't show stale matches.
        Map<UUID, JobRecommendation> existing = recommendations.findByUserIdOrderByMatchScoreDesc(userId)
                .stream().collect(HashMap::new, (m, r) -> m.put(r.getJobId(), r), HashMap::putAll);
        Set<UUID> keptJobIds = new HashSet<>();

        // Resolved once per refresh, not per job — the audit FK to the profile version that
        // produced these signals (only meaningful when the source is the canonical profile).
        UUID profileVersionId = recommendationAuditEnabled && "PROFILE".equals(signals.source())
                ? profileVersions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .findFirst().map(v -> v.getId()).orElse(null)
                : null;

        int written = 0;
        for (Scored s : ranked) {
            Job job = s.job();
            JobScoring.ScoreResultV2 r = s.result();
            JobRecommendation rec = existing.getOrDefault(job.getId(),
                    JobRecommendation.builder().userId(userId).jobId(job.getId()).build());
            rec.setResumeId(resumeId);
            rec.setMatchScore(r.matchScore());
            rec.setMatchingSkills(String.join(",", r.matchedSkills()));
            rec.setMissingSkills(String.join(",", r.missingSkills()));
            rec.setRecommendationReason(reason(r, job));
            rec.setConfidenceLevel(r.confidence());
            rec.setScoreBreakdown(writeJson(r.breakdown()));
            // Phase 2B-1 / 2C-2: stamp the action category + MUST_APPLY flag (pure functions of the
            // score/breakdown). Flag-gated — when off, both stay null and the row is identical to today.
            if (categorizer.isEnabled()) {
                rec.setCategory(categorizer.categorize(r.matchScore()).name());
                rec.setMustApply(mustApplyEvaluator.isMustApply(job, r));
            }
            // Phase 2C-1: stamp the priority band + raw priority number (separate ranking dimension;
            // never re-orders the list). Flag-gated independently — off leaves both null.
            if (priorityEngine.isEnabled()) {
                var p = priorityEngine.compute(job, r);
                rec.setPriority(p.level().name());
                rec.setPriorityScore(p.priorityScore());
            }
            recommendations.save(rec);
            keptJobIds.add(job.getId());
            written++;

            if (recommendationAuditEnabled) {
                writeRecommendationAudit(userId, job.getId(), profileVersionId, signals.source(), r);
            }
        }
        // Drop previously-persisted recommendations that no longer pass the gate (e.g. tightened
        // rules, or the job's enrichment changed), so Recommended stays consistent with the gate.
        List<JobRecommendation> stale = existing.values().stream()
                .filter(rec -> !keptJobIds.contains(rec.getJobId()))
                .toList();
        if (!stale.isEmpty()) recommendations.deleteAll(stale);

        log.debug("Matcher persisted {} recommendations ({} removed) for user {}", written, stale.size(), userId);
        matchCache.markRefreshed(userId, resumeId, poolVersion);
        return written;
    }

    /**
     * Batch-load the AI-enriched skill signal for the scored pool: {@code jobId -> comma-joined
     * normalized_skills}. Empty when {@code jobs.matching.use-enrichment} is off, so the matcher
     * falls back to raw {@code jobs.skills} for every job (today's behavior). One query for the pool.
     */
    private Map<UUID, String> loadEnrichedSkills(List<Job> pool) {
        if (!useEnrichment || pool.isEmpty()) return Map.of();
        List<UUID> ids = pool.stream().map(Job::getId).toList();
        Map<UUID, String> out = new HashMap<>();
        for (JobAiEnrichment e : enrichment.findByJobIdIn(ids)) {
            List<String> sk = JsonLists.toList(e.getNormalizedSkillsJson());
            if (!sk.isEmpty()) out.put(e.getJobId(), String.join(",", sk));
        }
        return out;
    }

    /**
     * Phase 6.5 — applies the additive learning boost/penalty on top of an already-computed v2 score,
     * clamped back to 0-100. Returns {@code base} unchanged (same object) when no learning source is
     * active, so stock (dark) behavior is byte-for-byte identical to before this integration.
     */
    private JobScoring.ScoreResultV2 applyLearningBoost(UUID userId, Job job, JobScoring.ScoreResultV2 base) {
        if (!learningBooster.isActive()) return base;
        int boost = learningBooster.computeBoost(userId, job, base.matchedSkills());
        if (boost == 0) return base;
        int adjusted = Math.min(100, Math.max(0, base.matchScore() + boost));
        return new JobScoring.ScoreResultV2(adjusted, base.matchedSkills(), base.missingSkills(),
                base.breakdown().withLearningBoost(boost), base.confidence(),
                base.matchedSkillFamilyCount(), base.matchedRoleCount());
    }

    /**
     * Phase 13C — applies the production-evidence adjustment (±10 max) on top of the career-memory
     * boost, the fourth link in this same chain and the same clamping as its three predecessors.
     * Returns {@code base} unchanged (same object) when the booster is dark or the snapshot has
     * nothing actionable, so stock ranking is byte-for-byte identical until
     * {@code jobs.matching.production-intelligence-boost.enabled} is explicitly turned on.
     *
     * <p>Takes the snapshot rather than a {@code userId} deliberately: it is resolved once for the
     * whole pool by the caller, so this method issues no query per job.
     */
    private JobScoring.ScoreResultV2 applyProductionIntelligenceBoost(
            ai.careerpilot.intelligence.ProductionOptimizationSnapshot snapshot,
            Job job, JobScoring.ScoreResultV2 base) {
        if (snapshot == null) return base;
        int boost = productionBooster.computeBoost(snapshot, job);
        if (boost == 0) return base;
        int adjusted = Math.min(100, Math.max(0, base.matchScore() + boost));
        return new JobScoring.ScoreResultV2(adjusted, base.matchedSkills(), base.missingSkills(),
                base.breakdown(), base.confidence(),
                base.matchedSkillFamilyCount(), base.matchedRoleCount());
    }

    /**
     * Resolves the snapshot for one refresh. Try/catch isolated: a failure in the intelligence
     * layer must degrade ranking to its pre-13C behaviour, never abort a recommendation refresh
     * the user is waiting on.
     */
    private ai.careerpilot.intelligence.ProductionOptimizationSnapshot safeProductionSnapshot(UUID userId) {
        try {
            return productionIntelligence.getSnapshot(userId);
        } catch (Exception e) {
            log.warn("RECO_MATCH production intelligence unavailable user={}: {}", userId, e.toString());
            return null;
        }
    }

    /**
     * Phase 7.13 — applies the company-knowledge boost/penalty (±5 max) on top of the learning
     * boost, same seam and clamping as {@link #applyLearningBoost}. Returns {@code base} unchanged
     * (same object) when the company graph is dark, so stock ranking is byte-for-byte identical.
     */
    private JobScoring.ScoreResultV2 applyCompanyKnowledgeBoost(UUID userId, Job job, JobScoring.ScoreResultV2 base) {
        if (!companyBooster.isActive()) return base;
        int boost = companyBooster.computeBoost(userId, job.getCompany());
        if (boost == 0) return base;
        int adjusted = Math.min(100, Math.max(0, base.matchScore() + boost));
        return new JobScoring.ScoreResultV2(adjusted, base.matchedSkills(), base.missingSkills(),
                base.breakdown(), base.confidence(),
                base.matchedSkillFamilyCount(), base.matchedRoleCount());
    }

    /**
     * Phase 7.15.1 — applies the career-memory boost/penalty (±5 max) on top of the company
     * knowledge boost, same seam and clamping as {@link #applyCompanyKnowledgeBoost}. Returns
     * {@code base} unchanged (same object) when memory or this specific boost is dark, so stock
     * ranking is byte-for-byte identical to today until BOTH {@code career.memory.enabled} AND
     * {@code jobs.matching.career-memory-boost.enabled} are explicitly turned on.
     */
    private JobScoring.ScoreResultV2 applyCareerMemoryBoost(UUID userId, Job job, JobScoring.ScoreResultV2 base) {
        if (!careerMemoryBooster.isActive()) return base;
        int boost = careerMemoryBooster.computeBoost(userId, job);
        if (boost == 0) return base;
        int adjusted = Math.min(100, Math.max(0, base.matchScore() + boost));
        return new JobScoring.ScoreResultV2(adjusted, base.matchedSkills(), base.missingSkills(),
                base.breakdown(), base.confidence(),
                base.matchedSkillFamilyCount(), base.matchedRoleCount());
    }

    /** The skill signal to score a job against: enriched normalized_skills when present, else the raw column. */
    private String effectiveSkills(Job job, Map<UUID, String> enrichedSkills) {
        String enriched = enrichedSkills.get(job.getId());
        return enriched != null ? enriched : job.getSkills();
    }

    /** Industry/quality filter — drop excluded non-tech families unless they are the candidate's own. */
    private boolean isIndustryExcluded(Job job, String candidateFamily) {
        if (!industryFilterEnabled) return false;
        String family = job.getJobFamily();
        return taxonomy.isExcludedFamily(family) && !family.equals(candidateFamily);
    }

    /**
     * User-defined exclusion — delegates to the shared {@link RoleExclusionFilter} (also used by the
     * Domestic/International discovery tabs). Kept as a package-private method so the existing unit
     * tests can drive the exclusion behavior through the matcher.
     */
    boolean isRoleExcluded(Job job, List<String> excludedRoles) {   // package-private for unit tests
        return roleExclusion.isExcluded(job, excludedRoles);
    }

    /** Full-spec Recommended gate. When strict gate is off, only the legacy top-N-by-score applies. */
    private boolean passesGate(JobScoring.ScoreResultV2 r) {
        if (!strictGateEnabled) return true;
        return r.matchScore() >= gateMinScore
                && r.matchedRoleCount() >= 1
                && r.matchedSkillFamilyCount() >= gateMinSkillFamilies;
    }

    /**
     * Persist one {@code recommendation_audit} row for a scored job. Score-component mapping from
     * {@link JobScoring.ScoreBreakdown}: skill_score=skills, role_score=role, location_score=location,
     * visa_score=visa, salary_score=salary, preference_score=workMode (closest existing analog — there
     * is no separate "preference" component in {@code scoreV2}), final_score=matchScore. Never throws:
     * an audit-write failure must not affect the recommendation that was already persisted.
     */
    private void writeRecommendationAudit(UUID userId, UUID jobId, UUID profileVersionId,
                                          String profileSource, JobScoring.ScoreResultV2 r) {
        try {
            JobScoring.ScoreBreakdown b = r.breakdown();
            recommendationAudit.save(RecommendationAudit.builder()
                    .userId(userId)
                    .jobId(jobId)
                    .profileVersion(profileVersionId)
                    .profileSource(profileSource)
                    .skillScore(b.skills())
                    .roleScore(b.role())
                    .preferenceScore(b.workMode())
                    .locationScore(b.location())
                    .visaScore(b.visa())
                    .salaryScore(b.salary())
                    .finalScore(r.matchScore())
                    .build());
        } catch (Exception e) {
            log.warn("Recommendation audit write failed for user={} job={}: {}", userId, jobId, e.toString());
        }
    }

    private String writeJson(JobScoring.ScoreBreakdown b) {
        try {
            return mapper.writeValueAsString(b);
        } catch (Exception e) {
            return null;
        }
    }

    private String reason(JobScoring.ScoreResultV2 r, Job job) {
        if (r.matchScore() >= 75) {
            return "Strong match — aligns on " + r.matchedSkills().size() + " of your skills"
                    + (Boolean.TRUE.equals(job.getRemote()) ? "; remote-friendly." : ".");
        }
        if (r.matchScore() >= 50) {
            return "Partial match — covers " + r.matchedSkills().size() + " skills; gaps: "
                    + topFew(r.missingSkills()) + ".";
        }
        return "Stretch role — consider building: " + topFew(r.missingSkills()) + ".";
    }

    private static String topFew(List<String> xs) {
        return xs.isEmpty() ? "none flagged" : String.join(", ", xs.subList(0, Math.min(3, xs.size())));
    }
}
