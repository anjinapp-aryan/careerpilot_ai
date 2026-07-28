package ai.careerpilot.service;

import ai.careerpilot.api.dto.JobRecommendationDtos.CandidateProfileSummary;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJob;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.jobdiscovery.JobMatchingService;
import ai.careerpilot.jobdiscovery.JobScoring;
import ai.careerpilot.jobdiscovery.JobScoring.ScoreBreakdown;
import ai.careerpilot.jobdiscovery.international.InternationalJobRankingService;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import ai.careerpilot.service.profile.JsonLists;
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
    private final InternationalJobRankingService internationalRanking;
    private final JobScoring scoring;
    private final CandidateProfileRepository candidateProfiles;
    private final ResumeRepository resumes;
    private final ObjectMapper mapper = new ObjectMapper();

    /** When true, Recommended is gated to score >= threshold AND confidence >= MEDIUM. */
    private final boolean v2Enabled;
    private final int threshold;

    public JobRecommendationService(WorkflowRunRepository runs,
                                    JobRepository jobs,
                                    JobRecommendationRepository recommendations,
                                    JobMatchingService matching,
                                    InternationalJobRankingService internationalRanking,
                                    JobScoring scoring,
                                    CandidateProfileRepository candidateProfiles,
                                    ResumeRepository resumes,
                                    @Value("${jobs.recommendation.v2-enabled:true}") boolean v2Enabled,
                                    @Value("${jobs.recommendation.threshold:75}") int threshold) {
        this.runs = runs;
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.matching = matching;
        this.internationalRanking = internationalRanking;
        this.scoring = scoring;
        this.candidateProfiles = candidateProfiles;
        this.resumes = resumes;
        this.v2Enabled = v2Enabled;
        this.threshold = threshold;
    }

    public RecommendedJobsResponse recommend(UUID userId, UUID orgId, int limit) {
        return recommend(userId, orgId, 0, Math.max(1, limit), "all");
    }

    /**
     * Phase 2B-2 — explicitly recompute this user's persisted recommendations against the current
     * discovered pool, instead of relying on the implicit refresh that happens on a page-0 read.
     * Returns the number of recommendations written. Same cost/behavior as today's implicit refresh;
     * this just gives the client an explicit trigger (e.g. "Refresh matches" button) and a count.
     */
    public int rebuild(UUID userId) {
        int written = matching.refreshForUser(userId);
        refreshInternationalRankingSafely(userId);
        return written;
    }

    /**
     * International Job Discovery Engine, Phase 1 — rides the same refresh lifecycle as the
     * existing recommendation matcher rather than adding a new trigger. Try/catch isolated so a
     * ranking failure never blocks the existing recommendation refresh (matches the codebase-wide
     * discipline of {@code JobAggregationService.runProvider}). No-op when {@code
     * career.international.ranking.enabled} is off.
     */
    private void refreshInternationalRankingSafely(UUID userId) {
        try {
            internationalRanking.refreshForUser(userId);
        } catch (Exception e) {
            log.warn("International ranking refresh failed for user={}: {}", userId, e.toString());
        }
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

        // Phase 1 fallback: a canonical CandidateProfile (built from an uploaded Resume, via
        // CandidateProfileService.onResumeChanged) is a fully valid signal source on its own — the
        // matcher (CandidateSignalResolver) has resolved signals this way since Phase 1. Before this
        // fallback, a user with a CandidateProfile but no WorkflowRun got profile=null forever (read
        // by the frontend as "workflow never ran"), and matching.refreshForUser() was never even
        // invoked for them, so job_recommendations never populated either. Only consulted when there
        // is no WorkflowRun, so existing WorkflowRun-driven behavior is unchanged.
        CandidateProfile profileRow = latest == null ? candidateProfiles.findByUserId(userId).orElse(null) : null;
        if (latest == null && profileRow == null) {
            return new RecommendedJobsResponse(null, List.of(), pageNum, pageSize, 0, false);
        }

        List<String> extractedSkills;
        List<String> targetLocations;
        String targetRole;
        CandidateProfileSummary profile;

        if (latest != null) {
            Map<String, Object> state = parseState(latest);
            extractedSkills = stringList(state.get("extracted_skills"));
            targetLocations = stringList(state.get("target_locations"));
            Map<String, Object> candidateProfileState = asMap(state.get("candidate_profile"));

            profile = new CandidateProfileSummary(
                    intOrNull(candidateProfileState.get("years_experience")),
                    stringOrNull(candidateProfileState.get("current_title")),
                    extractedSkills,
                    preferredRoles(latest),
                    latest.getResumeScore());
            targetRole = latest.getTargetRole();
        } else {
            List<String> targetRoles = JsonLists.toList(profileRow.getTargetRolesJson());
            extractedSkills = JsonLists.toList(profileRow.getSkillsJson());
            targetLocations = JsonLists.toList(profileRow.getPreferredCountriesJson());
            targetRole = String.join(" ", targetRoles);

            profile = new CandidateProfileSummary(
                    profileRow.getYearsExperience(),
                    profileRow.getCurrentRole(),
                    extractedSkills,
                    targetRoles,
                    resumeScoreFor(profileRow));
        }

        // Prefer the real discovered pool: refresh + read persisted recommendations. Only recompute
        // on the first page — "Load more" pagination must not re-score the whole pool on every call.
        if (pageNum == 0) {
            matching.refreshForUser(userId);
            refreshInternationalRankingSafely(userId);
        }
        List<RecommendedJob> all = fromPersisted(userId, filter);

        // Fallback: no discovered recommendations yet → score the org pool on the fly (legacy).
        if (all.isEmpty()) {
            all = fromOrgPool(orgId, extractedSkills, targetRole, targetLocations, filter);
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
            if (!matchesRecFilter(job, rec, filter)) continue;

            out.add(new RecommendedJob(job, rec.getMatchScore(),
                    csv(rec.getMatchingSkills()), csv(rec.getMissingSkills()),
                    rec.getConfidenceLevel(), parseBreakdown(rec.getScoreBreakdown()), rec.getCategory(),
                    rec.getPriority(), rec.getPriorityScore(), rec.getMustApply()));
        }
        return out;
    }

    /**
     * Phase 2C: recommendation-aware filtering — the 2C "collections" (must-apply, human-review,
     * priority bands) key off the persisted {@code category}/{@code must_apply}/{@code priority}
     * columns, which only the persisted path has. Everything else falls through to the shared
     * job-attribute {@link #matchesFilter}. A collection filter matches nothing on rows where the
     * relevant column is null (categorization/priority flag was off) — correct: no data, no match.
     */
    private boolean matchesRecFilter(Job job, JobRecommendation rec, String filter) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter)) return true;
        return switch (filter.toLowerCase()) {
            case "must-apply" -> Boolean.TRUE.equals(rec.getMustApply());
            case "human-review" -> "HUMAN_REVIEW".equals(rec.getCategory());
            case "high-priority" -> "HIGH_PRIORITY".equals(rec.getCategory());
            case "auto-apply-ready" -> "AUTO_APPLY_READY".equals(rec.getCategory());
            default -> matchesFilter(job, rec.getMatchScore(), filter);
        };
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

    /** Resume score for the profile-only path — looked up via the profile's linked resume. */
    private Integer resumeScoreFor(CandidateProfile profile) {
        if (profile.getResumeId() == null) return null;
        return resumes.findById(profile.getResumeId()).map(Resume::getResumeScore).orElse(null);
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
