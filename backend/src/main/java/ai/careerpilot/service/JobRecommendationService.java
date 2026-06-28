package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.CandidateProfileSummary;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJob;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.jobdiscovery.JobMatchingService;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.JobScoring.ScoreBreakdown;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * "Recommended Jobs": deterministic, AI-free scoring against the candidate snapshot produced
 * by the LangGraph workflow (persisted in {@code WorkflowRun.state}).
 *
 * <p>Phase 2: when the global discovered-job pool is populated, this refreshes and reads
 * persisted {@code job_recommendations} ({@link JobMatchingService}). When the pool is empty
 * (e.g. discovery hasn't run, or an org only uses manually-added jobs) it falls back to the
 * original on-the-fly scoring of the org job pool — so existing behavior never regresses. The
 * response shape is unchanged either way.
 */
@Service
public class JobRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(JobRecommendationService.class);

    private final WorkflowRunRepository runs;
    private final JobRepository jobs;
    private final JobRecommendationRepository recommendations;
    private final JobMatchingService matching;
    private final JobScoring scoring;
    private final ObjectMapper mapper = new ObjectMapper();

    /** When true, Recommended is gated to score >= threshold AND confidence >= MEDIUM. */
    private final boolean v2Enabled;
    private final int threshold;

    public JobRecommendationService(WorkflowRunRepository runs,
                                    JobRepository jobs,
                                    JobRecommendationRepository recommendations,
                                    JobMatchingService matching,
                                    JobScoring scoring,
                                    @Value("${jobs.recommendation.v2-enabled:true}") boolean v2Enabled,
                                    @Value("${jobs.recommendation.threshold:75}") int threshold) {
        this.runs = runs;
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.matching = matching;
        this.scoring = scoring;
        this.v2Enabled = v2Enabled;
        this.threshold = threshold;
    }

    public RecommendedJobsResponse recommend(UUID userId, UUID orgId, int limit) {
        return recommend(userId, orgId, 0, Math.max(1, limit), "all");
    }

    public RecommendedJobsResponse recommend(UUID userId, UUID orgId, int limit, String filter) {
        return recommend(userId, orgId, 0, Math.max(1, limit), filter);
    }

    /**
     * Build the full gated+filtered+ranked list, then return one page of it. Filters and the
     * score threshold are applied identically on both the discovered-pool and org-pool paths,
     * so a chip never silently no-ops on the fallback path.
     */
    public RecommendedJobsResponse recommend(UUID userId, UUID orgId, int page, int size, String filter) {
        int pageSize = size <= 0 ? 10 : Math.min(size, 50);
        int pageNum = Math.max(0, page);

        WorkflowRun latest = runs.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .findFirst().orElse(null);
        if (latest == null) {
            return new RecommendedJobsResponse(null, List.of(), pageNum, pageSize, 0, false);
        }

        Map<String, Object> state = parseState(latest);
        List<String> extractedSkills = stringList(state.get("extracted_skills"));
        List<String> targetLocations = stringList(state.get("target_locations"));
        Map<String, Object> candidateProfile = asMap(state.get("candidate_profile"));

        CandidateProfileSummary profile = new CandidateProfileSummary(
                intOrNull(candidateProfile.get("years_experience")),
                stringOrNull(candidateProfile.get("current_title")),
                extractedSkills,
                preferredRoles(latest),
                latest.getResumeScore());

        // Prefer the real discovered pool: refresh + read persisted recommendations. Only recompute
        // on the first page — "Load more" pagination must not re-score the whole pool on every call.
        if (pageNum == 0) matching.refreshForUser(userId);
        List<RecommendedJob> all = fromPersisted(userId, filter);

        // Fallback: no discovered recommendations yet → score the org pool on the fly (legacy).
        if (all.isEmpty()) {
            all = fromOrgPool(orgId, extractedSkills, latest.getTargetRole(), targetLocations, filter);
        }

        // Paginate in memory (the gated list is small — <= KEEP_TOP).
        int total = all.size();
        int from = Math.min(pageNum * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<RecommendedJob> pageItems = all.subList(from, to);
        boolean hasMore = to < total;

        log.info("RECO_READ user={} filter={} matched={} page={} size={} returned={} hasMore={}",
                userId, filter, total, pageNum, pageSize, pageItems.size(), hasMore);

        return new RecommendedJobsResponse(profile, pageItems, pageNum, pageSize, total, hasMore);
    }

    /** All gated + filtered persisted recommendations, ranked by score (no pagination here). */
    private List<RecommendedJob> fromPersisted(UUID userId, String filter) {
        List<JobRecommendation> recs = recommendations.findByUserIdOrderByMatchScoreDesc(userId);
        if (recs.isEmpty()) return List.of();

        Map<UUID, Job> jobsById = new HashMap<>();
        jobs.findAllById(recs.stream().map(JobRecommendation::getJobId).toList())
                .forEach(j -> jobsById.put(j.getId(), j));

        List<RecommendedJob> out = new ArrayList<>();
        for (JobRecommendation rec : recs) {
            Job job = jobsById.get(rec.getJobId());
            if (job == null) continue; // recommendation outlived its job

            // Quality gate: only score >= threshold reaches Recommended; the rest fall through to
            // Browse (/api/jobs/pool). Confidence is shown as a badge, not used as a hard gate
            // (it was over-filtering and collapsing the list to the fallback path). Flag-gated.
            if (v2Enabled && rec.getMatchScore() < threshold) continue;
            if (!matchesFilter(job, rec.getMatchScore(), filter)) continue;

            out.add(new RecommendedJob(job, rec.getMatchScore(),
                    csv(rec.getMatchingSkills()), csv(rec.getMissingSkills()),
                    rec.getConfidenceLevel(), parseBreakdown(rec.getScoreBreakdown())));
        }
        return out;
    }

    /** Recommended-tab filter chips. {@code all} passes everything that cleared the gate. */
    private boolean matchesFilter(Job job, int matchScore, String filter) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter)) return true;
        return switch (filter.toLowerCase()) {
            case "remote" -> "REMOTE".equals(job.getRemoteType());
            case "hybrid" -> "HYBRID".equals(job.getRemoteType());
            case "onsite" -> "ONSITE".equals(job.getRemoteType());
            case "visa" -> Boolean.TRUE.equals(job.getSponsorshipAvailable());
            case "relocation" -> Boolean.TRUE.equals(job.getRelocationSupport());
            case "high" -> matchScore >= 90;
            case "new" -> job.getPostedDate() != null
                    && Duration.between(job.getPostedDate(), Instant.now()).toHours() <= 24;
            default -> true;
        };
    }

    private ScoreBreakdown parseBreakdown(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, ScoreBreakdown.class);
        } catch (Exception e) {
            return null; // old-shape breakdown from a pre-v2 row → re-scored on next refresh
        }
    }

    /** Org-pool fallback (legacy on-the-fly scoring), now also filter-aware and unlimited. */
    private List<RecommendedJob> fromOrgPool(UUID orgId, List<String> skills, String targetRole,
                                             List<String> targetLocations, String filter) {
        List<Job> pool = jobs.search(orgId, null, PageRequest.of(0, 100)).getContent();
        return pool.stream()
                .map(job -> {
                    JobScoring.ScoreResult r = scoring.score(job, skills, targetRole, targetLocations);
                    return new RecommendedJob(job, r.matchScore(), r.matchedSkills(), r.missingSkills());
                })
                .filter(rj -> matchesFilter(rj.job(), rj.matchScore(), filter))
                .sorted(Comparator.comparingInt(RecommendedJob::matchScore).reversed())
                .toList();
    }

    private List<String> preferredRoles(WorkflowRun run) {
        List<String> roles = new ArrayList<>();
        if (run.getTargetRole() != null && !run.getTargetRole().isBlank()) roles.add(run.getTargetRole());
        if (run.getTargetSeniority() != null && !run.getTargetSeniority().isBlank()) roles.add(run.getTargetSeniority());
        return roles;
    }

    private static List<String> csv(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
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

    private String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
