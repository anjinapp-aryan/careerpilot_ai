package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateMatchSignals;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
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

    private static final int POOL_LIMIT = 200;   // discovered jobs scored per refresh
    private static final int KEEP_TOP = 50;      // recommendations persisted per user
    private static final int ROLE_RELEVANCE_MIN = 40; // reject jobs whose role similarity < 40%

    private final CandidateSignalResolver signalResolver;
    private final JobRepository jobs;
    private final JobRecommendationRepository recommendations;
    private final JobScoring scoring;
    private final JobTaxonomy taxonomy;
    private final RoleExclusionFilter roleExclusion;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Full-spec Recommended gate: score >= minScore AND >= 1 role family AND >= 3 skill families. */
    private final boolean strictGateEnabled;
    private final int gateMinScore;
    private final int gateMinSkillFamilies;
    /** Exclude non-technical job families (Marketing/Sales/HR/…) from a tech candidate's matches. */
    private final boolean industryFilterEnabled;

    public JobMatchingService(CandidateSignalResolver signalResolver,
                              JobRepository jobs,
                              JobRecommendationRepository recommendations,
                              JobScoring scoring,
                              JobTaxonomy taxonomy,
                              RoleExclusionFilter roleExclusion,
                              @Value("${jobs.recommendation.strict-gate-enabled:true}") boolean strictGateEnabled,
                              @Value("${jobs.recommendation.gate-min-score:70}") int gateMinScore,
                              @Value("${jobs.recommendation.gate-min-skills:3}") int gateMinSkillFamilies,
                              @Value("${jobs.industry.filter-enabled:true}") boolean industryFilterEnabled) {
        this.signalResolver = signalResolver;
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.scoring = scoring;
        this.taxonomy = taxonomy;
        this.roleExclusion = roleExclusion;
        this.strictGateEnabled = strictGateEnabled;
        this.gateMinScore = gateMinScore;
        this.gateMinSkillFamilies = gateMinSkillFamilies;
        this.industryFilterEnabled = industryFilterEnabled;
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

        // 1) Quality filtering: drop user-excluded roles (hard filter), then non-technical families
        //    (industry filter), then clearly role-irrelevant jobs (relevance pre-gate). 2) Score what
        //    survives. 3) Apply the full Recommended gate (score + role + skills). 4) Rank, keep top N.
        //    Jobs that fail any gate are simply not persisted, so they surface under Browse instead.
        record Scored(Job job, JobScoring.ScoreResultV2 result) {}
        List<Scored> scored = pool.stream()
                .filter(j -> !isRoleExcluded(j, excludedRoles))
                .filter(j -> !isIndustryExcluded(j, candidateFamily))
                .filter(j -> {
                    int rs = scoring.roleSimilarity(j, skills, targetRole);
                    return rs < 0 || rs >= ROLE_RELEVANCE_MIN;
                })
                .map(j -> new Scored(j, scoring.scoreV2(j, ctx, prefs)))
                .filter(s -> passesGate(s.result()))
                .toList();
        List<Scored> ranked = scored.stream()
                .sorted(Comparator.comparingInt((Scored s) -> s.result().matchScore()).reversed())
                .limit(KEEP_TOP)
                .toList();
        log.info("RECO_MATCH user={} source={} pool={} gated={} persisted={} strictGate={} industryFilter={} excluded={}",
                userId, signals.source(), pool.size(), scored.size(), ranked.size(),
                strictGateEnabled, industryFilterEnabled, excludedRoles.size());

        // Batch-load this user's existing recommendation rows once (was an N+1 SELECT per job),
        // and remove rows that no longer qualify so the Recommended tab can't show stale matches.
        Map<UUID, JobRecommendation> existing = recommendations.findByUserIdOrderByMatchScoreDesc(userId)
                .stream().collect(HashMap::new, (m, r) -> m.put(r.getJobId(), r), HashMap::putAll);
        Set<UUID> keptJobIds = new HashSet<>();

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
            recommendations.save(rec);
            keptJobIds.add(job.getId());
            written++;
        }
        // Drop previously-persisted recommendations that no longer pass the gate (e.g. tightened
        // rules, or the job's enrichment changed), so Recommended stays consistent with the gate.
        List<JobRecommendation> stale = existing.values().stream()
                .filter(rec -> !keptJobIds.contains(rec.getJobId()))
                .toList();
        if (!stale.isEmpty()) recommendations.deleteAll(stale);

        log.debug("Matcher persisted {} recommendations ({} removed) for user {}", written, stale.size(), userId);
        return written;
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
