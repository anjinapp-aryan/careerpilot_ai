package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import ai.careerpilot.service.CandidatePreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Rule-based matcher (no LLM). Scores the global discovered-job pool against a user's
 * latest workflow snapshot using the shared {@link JobScoring}, then upserts the top
 * results into {@code job_recommendations} so the Recommended tab is a cheap indexed read.
 */
@Service
public class JobMatchingService {

    private static final Logger log = LoggerFactory.getLogger(JobMatchingService.class);

    private static final int POOL_LIMIT = 200;   // discovered jobs scored per refresh
    private static final int KEEP_TOP = 50;      // recommendations persisted per user
    private static final int ROLE_RELEVANCE_MIN = 40; // reject jobs whose role similarity < 40%

    private final WorkflowRunRepository runs;
    private final JobRepository jobs;
    private final JobRecommendationRepository recommendations;
    private final JobScoring scoring;
    private final CandidatePreferencesService preferences;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobMatchingService(WorkflowRunRepository runs,
                              JobRepository jobs,
                              JobRecommendationRepository recommendations,
                              JobScoring scoring,
                              CandidatePreferencesService preferences) {
        this.runs = runs;
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.scoring = scoring;
        this.preferences = preferences;
    }

    /**
     * Recompute and persist recommendations for a user against the current discovered pool.
     * Returns the number of recommendations written. No-op (returns 0) when the user has no
     * workflow snapshot or the discovered pool is empty.
     */
    @Transactional
    public int refreshForUser(UUID userId) {
        WorkflowRun latest = runs.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().orElse(null);
        if (latest == null) return 0;

        List<Job> pool = jobs.findDiscoveredPool(POOL_LIMIT);
        if (pool.isEmpty()) return 0;

        Map<String, Object> state = parseState(latest);
        List<String> skills = stringList(state.get("extracted_skills"));
        List<String> targetLocations = stringList(state.get("target_locations"));
        UUID resumeId = parseUuid(state.get("resume_id")); // stamped into the state blob, not a column

        var prefDto = preferences.get(userId);
        JobScoring.PreferenceContext prefs = prefDto.toScoringContext();
        // Effective role = workflow target role + the user's preferred roles, so role similarity
        // (and the relevance gate) consider all roles the candidate is actually targeting.
        String targetRole = combineRole(latest.getTargetRole(), prefDto.preferredRolesOrEmpty());

        Integer yearsExp = intOrNull(asMap(state.get("candidate_profile")).get("years_experience"));
        JobScoring.CandidateContext ctx = new JobScoring.CandidateContext(
                skills, targetRole, targetLocations, yearsExp, latest.getAtsScore());

        // Score, drop role-irrelevant jobs (relevance gate), sort, keep top N. The gate rejects
        // only when there is a real role signal below the threshold (e.g. PHP roles for a Java
        // Architect); jobs we can't compare (roleSimilarity = -1) are kept and ranked on merit.
        record Scored(Job job, JobScoring.ScoreResultV2 result) {}
        List<Scored> relevant = pool.stream()
                .filter(j -> {
                    int rs = scoring.roleSimilarity(j, skills, targetRole);
                    return rs < 0 || rs >= ROLE_RELEVANCE_MIN;
                })
                .map(j -> new Scored(j, scoring.scoreV2(j, ctx, prefs)))
                .toList();
        List<Scored> ranked = relevant.stream()
                .sorted(Comparator.comparingInt((Scored s) -> s.result().matchScore()).reversed())
                .limit(KEEP_TOP)
                .toList();
        log.info("RECO_MATCH user={} pool={} relevant={} persisted={}",
                userId, pool.size(), relevant.size(), ranked.size());

        int written = 0;
        for (Scored s : ranked) {
            Job job = s.job();
            JobScoring.ScoreResultV2 r = s.result();
            JobRecommendation rec = recommendations.findByUserIdAndJobId(userId, job.getId())
                    .orElseGet(() -> JobRecommendation.builder().userId(userId).jobId(job.getId()).build());
            rec.setResumeId(resumeId);
            rec.setMatchScore(r.matchScore());
            rec.setMatchingSkills(String.join(",", r.matchedSkills()));
            rec.setMissingSkills(String.join(",", r.missingSkills()));
            rec.setRecommendationReason(reason(r, job));
            rec.setConfidenceLevel(r.confidence());
            rec.setScoreBreakdown(writeJson(r.breakdown()));
            recommendations.save(rec);
            written++;
        }
        log.debug("Matcher persisted {} recommendations for user {}", written, userId);
        return written;
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

    /** Merge the workflow target role with the user's preferred roles into one role string. */
    private static String combineRole(String targetRole, List<String> preferredRoles) {
        StringBuilder sb = new StringBuilder();
        if (targetRole != null && !targetRole.isBlank()) sb.append(targetRole);
        if (preferredRoles != null) {
            for (String r : preferredRoles) {
                if (r != null && !r.isBlank()) sb.append(' ').append(r);
            }
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseState(WorkflowRun run) {
        try {
            return mapper.readValue(run.getState(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID parseUuid(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
