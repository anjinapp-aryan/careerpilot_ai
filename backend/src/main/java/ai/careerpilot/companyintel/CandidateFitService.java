package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.memory.CareerMemoryBooster;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.17 — Candidate Fit unification. Reuses two ALREADY-BUILT, independently-scored engines
 * rather than creating a third scoring system:
 * <ul>
 *   <li>{@code JobScoring}'s per-JOB 7-factor breakdown (skills/experience/role/location/salary/
 *       visa/workMode), persisted by {@code JobMatchingService} onto
 *       {@link JobRecommendation#getScoreBreakdown()} as JSON.</li>
 *   <li>{@link CompanyKnowledgeService} — the per-COMPANY 10 deterministic scores (Phase 7.13),
 *       read via {@code findByName} exactly as {@code CompanyKnowledgeBooster} already does.</li>
 * </ul>
 * {@link CareerMemoryBooster#computeBoost} supplies "Memory Influence" verbatim — the exact ±5
 * nudge {@code JobMatchingService} already applies to the match score.
 *
 * <p><b>Never fabricates.</b> The spec's Leadership/Architecture/Domain/Cloud Fit dimensions have
 * no backing data source anywhere in this codebase (confirmed by architecture review) — rather
 * than invent a formula, this service reports them as {@code null} with an explicit "not computed"
 * explanation. Every other dimension is null (not a guessed number) whenever its source row is
 * missing (no {@link JobRecommendation} yet, no {@link CompanyKnowledge} yet).
 *
 * <p>Ships dark via {@code company.candidate-fit.enabled}, independent of the two engines' own
 * flags — reading this endpoint never re-triggers scoring; it only reads what those engines have
 * already computed and persisted.
 */
@Service
public class CandidateFitService {

    private final JobRepository jobs;
    private final JobRecommendationRepository jobRecommendations;
    private final CompanyKnowledgeService companyKnowledgeService;
    private final CareerMemoryBooster memoryBooster;
    private final KnowledgeAggregator aggregator;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public CandidateFitService(JobRepository jobs,
                               JobRecommendationRepository jobRecommendations,
                               CompanyKnowledgeService companyKnowledgeService,
                               CareerMemoryBooster memoryBooster,
                               KnowledgeAggregator aggregator,
                               @Value("${company.candidate-fit.enabled:false}") boolean enabled) {
        this.jobs = jobs;
        this.jobRecommendations = jobRecommendations;
        this.companyKnowledgeService = companyKnowledgeService;
        this.memoryBooster = memoryBooster;
        this.aggregator = aggregator;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<Map<String, Object>> explainFit(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        Job job = jobs.findById(jobId).orElse(null);
        if (job == null) return Optional.empty();

        JobRecommendation rec = jobRecommendations.findByUserIdAndJobId(userId, jobId).orElse(null);
        Map<String, Object> breakdown = rec == null ? null : parseJson(rec.getScoreBreakdown());
        CompanyKnowledge knowledge = companyKnowledgeService.findByName(userId, job.getCompany()).orElse(null);
        Map<String, String> companyWhy = knowledge == null ? null : aggregator.parse(knowledge.getScoreExplanations());

        Map<String, Object> out = new LinkedHashMap<>();

        // ── Job-level dimensions (JobScoring, via the persisted JobRecommendation) ──
        putDimension(out, "skillFit", intOf(breakdown, "skills"),
                rec != null ? "matched: " + nullToEmpty(rec.getMatchingSkills()) + "; missing: " + nullToEmpty(rec.getMissingSkills())
                        : "no job recommendation on file yet for this application");
        putDimension(out, "experienceFit", intOf(breakdown, "experience"),
                breakdown != null ? "from JobScoring's seniority-aware experience comparison" : "no job recommendation on file yet");
        putDimension(out, "locationFit", intOf(breakdown, "location"),
                breakdown != null ? "from JobScoring's location/remote-preference comparison" : "no job recommendation on file yet");
        putDimension(out, "salaryFit", intOf(breakdown, "salary"),
                breakdown != null ? "from JobScoring's salary-band comparison" : "no job recommendation on file yet");
        putDimension(out, "visaFit", intOf(breakdown, "visa"),
                breakdown != null ? "from JobScoring's sponsorship-requirement comparison" : "no job recommendation on file yet");

        // ── Company-level dimensions (CompanyKnowledge, Phase 7.13) ──
        putDimension(out, "technologyFit", knowledge != null ? knowledge.getTechnologyMatch() : null,
                explanationOr(companyWhy, "technologyMatch", "no company knowledge on file for " + job.getCompany()));
        putDimension(out, "careerGoalFit", knowledge != null ? knowledge.getCareerGrowth() : null,
                knowledge != null
                        ? "derived from this company's observed career-growth signal (" + explanationOr(companyWhy, "careerGrowth", "")
                        + ") — not a personalized career-goal matcher, which doesn't exist yet"
                        : "no company knowledge on file for " + job.getCompany());

        // ── Dimensions with no backing data source anywhere in the codebase — honestly UNKNOWN ──
        putDimension(out, "leadershipFit", null, "not computed — no leadership-fit data source exists yet");
        putDimension(out, "architectureFit", null, "not computed — no architecture-fit data source exists yet");
        putDimension(out, "domainFit", null, "not computed — no domain-fit data source exists yet");
        putDimension(out, "cloudFit", null, "not computed — no cloud-fit data source exists yet");

        // ── Memory Influence (Phase 7.15.1/7.15.2, unchanged) ──
        int memoryInfluence = memoryBooster.computeBoost(userId, job);
        putDimension(out, "memoryInfluence", memoryInfluence,
                memoryInfluence == 0
                        ? "no remembered company/technology decisions influenced this job"
                        : "adjusted by " + memoryInfluence + " based on remembered company/technology preferences");

        // ── Overall Fit — a documented average of the two independent engines' top-line scores,
        // never a new formula. Null when neither source exists. ──
        Integer jobMatch = rec != null ? rec.getMatchScore() : null;
        Integer companyQuality = knowledge != null ? knowledge.getQualityScore() : null;
        Integer overall = overallFit(jobMatch, companyQuality);
        putDimension(out, "overallFit", overall,
                jobMatch != null && companyQuality != null
                        ? "average of job match score (" + jobMatch + ") and company quality score (" + companyQuality + ")"
                        : jobMatch != null ? "job match score only — no company knowledge on file for " + job.getCompany()
                        : companyQuality != null ? "company quality score only — no job recommendation on file yet"
                        : "no job recommendation or company knowledge on file yet");

        out.put("sources", Map.of(
                "jobRecommendation", rec != null,
                "companyKnowledge", knowledge != null));
        return Optional.of(out);
    }

    private static Integer overallFit(Integer jobMatch, Integer companyQuality) {
        if (jobMatch == null && companyQuality == null) return null;
        if (jobMatch == null) return companyQuality;
        if (companyQuality == null) return jobMatch;
        return Math.round((jobMatch + companyQuality) / 2.0f);
    }

    private static void putDimension(Map<String, Object> out, String key, Integer value, String explanation) {
        out.put(key, value);
        out.put(key + "Explanation", explanation);
    }

    private static Integer intOf(Map<String, Object> breakdown, String key) {
        if (breakdown == null) return null;
        Object v = breakdown.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    private static String explanationOr(Map<String, String> why, String key, String fallback) {
        if (why == null) return fallback;
        String v = why.get(key);
        return v != null ? v : fallback;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "none" : s;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

}
