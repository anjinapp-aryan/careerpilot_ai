package ai.careerpilot.dailydiscovery;

import ai.careerpilot.discovery.relevance.CareerRelevanceEvaluator;
import ai.careerpilot.discovery.relevance.CareerRelevanceResult;
import ai.careerpilot.discovery.relevance.RelevanceCandidateContext;
import ai.careerpilot.domain.DailyDiscoveryAnalytics;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.JobMatchingService;
import ai.careerpilot.repo.DailyDiscoveryAnalyticsRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Phase 5J/5K/5L/5M — per-user pass of the daily pipeline: reruns the existing rule-based
 * {@link JobMatchingService} (skill/experience/role/domain scoring is entirely reused, never
 * duplicated), then reads back the persisted {@code job_recommendations} + the existing
 * {@link CareerRelevanceEvaluator} to classify and aggregate today's results into one
 * {@link DailyDiscoveryAnalytics} row. Never throws — a per-user failure must not abort the run
 * for other users; callers isolate it via try/catch + dead-letter.
 */
@Service
public class DailyJobDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DailyJobDiscoveryService.class);

    private final JobMatchingService matching;
    private final JobRecommendationRepository recommendations;
    private final JobRepository jobs;
    private final CareerRelevanceEvaluator relevanceEvaluator;
    private final CandidateSignalResolver signalResolver;
    private final DailyDiscoveryAnalyticsRepository analyticsRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ai.careerpilot.companyintel.CompanyKnowledgeService companyKnowledge;

    public DailyJobDiscoveryService(JobMatchingService matching,
                                    JobRecommendationRepository recommendations,
                                    JobRepository jobs,
                                    CareerRelevanceEvaluator relevanceEvaluator,
                                    CandidateSignalResolver signalResolver,
                                    DailyDiscoveryAnalyticsRepository analyticsRepo,
                                    ai.careerpilot.companyintel.CompanyKnowledgeService companyKnowledge) {
        this.matching = matching;
        this.recommendations = recommendations;
        this.jobs = jobs;
        this.relevanceEvaluator = relevanceEvaluator;
        this.signalResolver = signalResolver;
        this.analyticsRepo = analyticsRepo;
        this.companyKnowledge = companyKnowledge;
    }

    /** Result of one user's daily pass — fed both into the persisted analytics row and the AI summary. */
    public record UserDiscoverySnapshot(
            int recommendedJobs, int mustApplyJobs, int highPriorityJobs, int humanReviewJobs,
            int hiddenJobs, int domesticJobs, int internationalJobs, BigDecimal averageScore,
            List<String> topCompanies, List<String> topSkills,
            Map<String, Integer> industryDistribution, Map<String, Integer> skillDistribution,
            Map<String, Integer> companyDistribution, Map<String, Integer> matchStrengthDistribution) {}

    /** Rerun matching for this user and persist today's aggregate analytics row. Never throws. */
    public UserDiscoverySnapshot processUser(UUID runId, UUID userId) {
        matching.refreshForUser(userId);
        List<JobRecommendation> recs = recommendations.findByUserIdOrderByMatchScoreDesc(userId);
        UserDiscoverySnapshot snapshot = classify(userId, recs);
        persist(runId, userId, snapshot);
        ingestCompanyKnowledge(userId, recs);
        return snapshot;
    }

    /**
     * Phase 7.13 — Daily Discovery as a knowledge source: what a recommended job's row already tells
     * us about its company flows into the Company Knowledge Graph. Silent no-op when
     * {@code company.knowledge.enabled} is off; never allowed to break the daily run.
     */
    private void ingestCompanyKnowledge(UUID userId, List<JobRecommendation> recs) {
        if (!companyKnowledge.isEnabled() || recs.isEmpty()) return;
        try {
            for (Job job : jobs.findAllById(recs.stream().map(JobRecommendation::getJobId).limit(50).toList())) {
                companyKnowledge.ingestJob(userId, job, null,
                        ai.careerpilot.companyintel.KnowledgeSource.DAILY_DISCOVERY);
            }
        } catch (Exception e) {
            log.warn("DAILY_DISCOVERY company-knowledge ingest failed user={}: {}", userId, e.toString());
        }
    }

    private UserDiscoverySnapshot classify(UUID userId, List<JobRecommendation> recs) {
        if (recs.isEmpty()) {
            return new UserDiscoverySnapshot(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                    List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        Map<UUID, Job> jobById = new HashMap<>();
        jobs.findAllById(recs.stream().map(JobRecommendation::getJobId).toList())
                .forEach(j -> jobById.put(j.getId(), j));

        String homeCountry = signalResolver.resolveLocationSignals(userId).homeCountry();
        boolean relevanceOn = relevanceEvaluator.isEnabled();
        RelevanceCandidateContext ctx = relevanceOn ? relevanceEvaluator.resolveContext(userId) : null;

        int mustApply = 0, highPriority = 0, humanReview = 0, hidden = 0, domestic = 0, international = 0;
        long scoreSum = 0;
        Map<String, Integer> industry = new LinkedHashMap<>();
        Map<String, Integer> skill = new LinkedHashMap<>();
        Map<String, Integer> company = new LinkedHashMap<>();
        Map<String, Integer> strength = new LinkedHashMap<>();

        for (JobRecommendation rec : recs) {
            scoreSum += rec.getMatchScore();
            Job job = jobById.get(rec.getJobId());

            if (Boolean.TRUE.equals(rec.getMustApply())) mustApply++;
            if ("HIGH_PRIORITY".equals(rec.getCategory()) || "AUTO_APPLY_READY".equals(rec.getCategory())) highPriority++;
            if ("HUMAN_REVIEW".equals(rec.getCategory())) humanReview++;

            if (job == null) continue;
            bump(company, job.getCompany());
            bump(industry, job.getJobFamily());
            for (String s : csvList(rec.getMatchingSkills())) bump(skill, s);

            String country = job.getCountry();
            if (country != null && !country.isBlank() && homeCountry != null
                    && country.equalsIgnoreCase(homeCountry)) {
                domestic++;
            } else {
                international++;
            }

            if (relevanceOn) {
                CareerRelevanceResult result = relevanceEvaluator.evaluateForScope(job, ctx, "domestic");
                bump(strength, result.strength().name());
                if (!result.visible()) hidden++;
            }
        }

        BigDecimal avg = BigDecimal.valueOf(scoreSum).divide(BigDecimal.valueOf(recs.size()), 2, RoundingMode.HALF_UP);
        List<String> topCompanies = topN(company, 5);
        List<String> topSkills = topN(skill, 5);

        return new UserDiscoverySnapshot(recs.size(), mustApply, highPriority, humanReview, hidden,
                domestic, international, avg, topCompanies, topSkills, industry, skill, company, strength);
    }

    private void persist(UUID runId, UUID userId, UserDiscoverySnapshot s) {
        try {
            analyticsRepo.save(DailyDiscoveryAnalytics.builder()
                    .runId(runId).userId(userId)
                    .domesticJobs(s.domesticJobs()).internationalJobs(s.internationalJobs())
                    .recommendedJobs(s.recommendedJobs()).mustApplyJobs(s.mustApplyJobs())
                    .highPriorityJobs(s.highPriorityJobs()).humanReviewJobs(s.humanReviewJobs())
                    .hiddenJobs(s.hiddenJobs()).averageScore(s.averageScore())
                    .industryDistribution(toJson(s.industryDistribution()))
                    .skillDistribution(toJson(s.skillDistribution()))
                    .companyDistribution(toJson(s.companyDistribution()))
                    .matchStrengthDistribution(toJson(s.matchStrengthDistribution()))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist daily discovery analytics for user={} run={}: {}", userId, runId, e.toString());
        }
    }

    /** Pipeline-wide aggregate row (user_id NULL) summed across every per-user snapshot in this run. */
    public void persistAggregate(UUID runId, List<UserDiscoverySnapshot> all) {
        if (all.isEmpty()) return;
        try {
            int domestic = 0, international = 0, recommended = 0, mustApply = 0, highPriority = 0, humanReview = 0, hidden = 0;
            long scoreSum = 0;
            for (UserDiscoverySnapshot s : all) {
                domestic += s.domesticJobs(); international += s.internationalJobs();
                recommended += s.recommendedJobs(); mustApply += s.mustApplyJobs();
                highPriority += s.highPriorityJobs(); humanReview += s.humanReviewJobs(); hidden += s.hiddenJobs();
                scoreSum += s.averageScore().multiply(BigDecimal.valueOf(Math.max(s.recommendedJobs(), 1))).longValue();
            }
            BigDecimal avg = recommended == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(scoreSum).divide(BigDecimal.valueOf(recommended), 2, RoundingMode.HALF_UP);
            analyticsRepo.save(DailyDiscoveryAnalytics.builder()
                    .runId(runId).userId(null)
                    .domesticJobs(domestic).internationalJobs(international)
                    .recommendedJobs(recommended).mustApplyJobs(mustApply)
                    .highPriorityJobs(highPriority).humanReviewJobs(humanReview).hiddenJobs(hidden)
                    .averageScore(avg)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist aggregate daily discovery analytics for run={}: {}", runId, e.toString());
        }
    }

    private static void bump(Map<String, Integer> m, String key) {
        if (key == null || key.isBlank()) return;
        m.merge(key.trim(), 1, Integer::sum);
    }

    private static List<String> csvList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    private String toJson(Map<String, Integer> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }
}
