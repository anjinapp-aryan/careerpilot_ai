package ai.careerpilot.learning.career.executive;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.learning.career.goal.PromotionReadinessService;
import ai.careerpilot.learning.career.goal.SkillGapIntelligenceService;
import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.19.5 — Executive Decision Engine. Architecture review found this codebase already has
 * every scoring/recommendation/memory/strategy primitive the spec's "Executive Coach" needs
 * ({@link PromotionReadinessService}, {@link SkillGapIntelligenceService}, {@code JobMatchingService}
 * output on {@link JobRecommendation}, {@link InterviewRepository}, {@code CareerStrategyEngine}'s
 * {@link CareerStrategy#getCareerSuccessProbability()}) — so this engine computes NOTHING new. It
 * only reads those existing outputs and turns them into a small, evidence-backed decision list.
 *
 * <p>Per explicit scope confirmation: only decision types with a real backing evidence chain are
 * computed (APPLY_NOW, WAIT_BEFORE_APPLYING, STUDY_NEXT, PREPARE_INTERVIEW, SWITCH_GOAL). Every
 * other decision type the spec names (Negotiate Offer, Request Referral, Update Resume, Focus
 * &lt;geography&gt;, Stop Applying, Take Certification) has no evidence source in this platform today
 * and is listed in {@code omittedDecisionTypes} with a reason, never fabricated.
 */
@Service
public class ExecutiveDecisionEngine {

    private static final int WAIT_READINESS_THRESHOLD = 65;
    private static final int SWITCH_GOAL_READINESS_DELTA = 20;

    private final SkillGapIntelligenceService skillGap;
    private final PromotionReadinessService promotionReadiness;
    private final JobRecommendationRepository jobRecommendations;
    private final JobRepository jobs;
    private final InterviewRepository interviews;
    private final CareerStrategyRepository strategies;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutiveDecisionEngine(SkillGapIntelligenceService skillGap,
                                   PromotionReadinessService promotionReadiness,
                                   JobRecommendationRepository jobRecommendations,
                                   JobRepository jobs,
                                   InterviewRepository interviews,
                                   CareerStrategyRepository strategies,
                                   @Value("${executive.decision.enabled:false}") boolean enabled) {
        this.skillGap = skillGap;
        this.promotionReadiness = promotionReadiness;
        this.jobRecommendations = jobRecommendations;
        this.jobs = jobs;
        this.interviews = interviews;
        this.strategies = strategies;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> decide(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) return out;

        List<Map<String, Object>> decisions = new ArrayList<>();
        List<String> omitted = new ArrayList<>();

        CareerStrategy strategy = strategies.findByUserId(userId).orElse(null);
        out.put("careerHealth", careerHealth(strategy));

        applyNow(userId, decisions, omitted);

        Map<String, Object> readinessByLevel = Map.of();
        if (promotionReadiness.isEnabled()) {
            Map<String, Object> readiness = promotionReadiness.compute(userId);
            readinessByLevel = (Map<String, Object>) readiness.getOrDefault("readinessByLevel", Map.of());
            waitBeforeApplying(readinessByLevel, decisions, omitted);
        } else {
            omitted.add("WAIT_BEFORE_APPLYING - PromotionReadinessService disabled (career.promotion-readiness.enabled)");
        }

        studyNext(userId, decisions, omitted);
        prepareInterview(userId, decisions, omitted);
        switchGoal(strategy, readinessByLevel, decisions, omitted);

        omitted.add("NEGOTIATE_OFFER - no evidence source exists (Offer rows are not indexed by role/level to compute a negotiation trigger)");
        omitted.add("REQUEST_REFERRAL - no referral/network data exists anywhere in this platform");
        omitted.add("UPDATE_RESUME - no resume-quality-delta trigger exists in this platform");
        omitted.add("FOCUS_GEOGRAPHY - no geographic hiring-trend aggregation exists in this platform");
        omitted.add("STOP_APPLYING - no burnout/diminishing-returns signal exists in this platform");
        omitted.add("TAKE_CERTIFICATION - certification suggestions are already surfaced under STUDY_NEXT evidence, not a separate decision type");

        out.put("decisions", decisions);
        out.put("omittedDecisionTypes", omitted);
        out.put("sourceModules", List.of("JobMatchingService", "PromotionReadinessService", "SkillGapIntelligenceService",
                "InterviewRepository", "CareerStrategyEngine", "CareerGoalPlannerService"));
        out.put("computedAt", Instant.now());
        return out;
    }

    private Map<String, Object> careerHealth(CareerStrategy strategy) {
        if (strategy != null && strategy.getCareerSuccessProbability() != null) {
            return Map.of(
                    "value", strategy.getCareerSuccessProbability(),
                    "source", "CareerStrategyEngine.careerSuccessProbability",
                    "evidence", "computed by the probability engine (learning.adaptive-career.enabled)");
        }
        return Map.of(
                "value", "NOT_COMPUTED",
                "reason", "career strategy probability engine has not run for this user yet (learning.adaptive-career.enabled)");
    }

    private void applyNow(UUID userId, List<Map<String, Object>> decisions, List<String> omitted) {
        List<JobRecommendation> recs = jobRecommendations.findByUserIdOrderByMatchScoreDesc(userId);
        if (recs.isEmpty()) {
            omitted.add("APPLY_NOW - no job recommendations exist for this user yet");
            return;
        }
        List<JobRecommendation> readyToApply = recs.stream()
                .filter(r -> "AUTO_APPLY_READY".equals(r.getCategory()) || Boolean.TRUE.equals(r.getMustApply()))
                .sorted(Comparator.comparingInt(JobRecommendation::getMatchScore).reversed())
                .limit(3)
                .toList();

        List<Map<String, Object>> evidenceJobs = readyToApply.stream().map(r -> {
            Optional<Job> job = jobs.findById(r.getJobId());
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("jobId", r.getJobId());
            j.put("company", job.map(Job::getCompany).orElse(null));
            j.put("title", job.map(Job::getTitle).orElse(null));
            j.put("matchScore", r.getMatchScore());
            return j;
        }).toList();

        decisions.add(decision("APPLY_NOW",
                readyToApply.isEmpty()
                        ? "No jobs currently meet the auto-apply threshold"
                        : "Apply now to " + evidenceJobs.size() + " job(s) that meet the auto-apply criteria",
                Map.of("jobs", evidenceJobs, "totalRecommendationsAnalyzed", recs.size()),
                readyToApply.isEmpty() ? "LOW" : (readyToApply.size() >= 2 ? "HIGH" : "MEDIUM"),
                List.of("JobMatchingService", "JobRecommendation.category")));
    }

    private void waitBeforeApplying(Map<String, Object> readinessByLevel, List<Map<String, Object>> decisions, List<String> omitted) {
        Optional<Map.Entry<String, Object>> weakest = readinessByLevel.entrySet().stream()
                .filter(e -> e.getValue() instanceof Map<?, ?> m && m.containsKey("readinessScore")
                        && ((Number) m.get("readinessScore")).intValue() < WAIT_READINESS_THRESHOLD)
                .min(Comparator.comparingInt(e -> ((Number) ((Map<?, ?>) e.getValue()).get("readinessScore")).intValue()));

        if (weakest.isEmpty()) {
            omitted.add("WAIT_BEFORE_APPLYING - no target level currently shows readiness below the " + WAIT_READINESS_THRESHOLD + "/100 threshold, or none is computable yet");
            return;
        }
        Map<?, ?> level = (Map<?, ?>) weakest.get().getValue();
        int score = ((Number) level.get("readinessScore")).intValue();
        decisions.add(decision("WAIT_BEFORE_APPLYING",
                "Hold off applying to " + weakest.get().getKey() + " roles — build readiness first",
                Map.of("targetLevel", weakest.get().getKey(), "readinessScore", score, "evidence", level.get("evidence")),
                score < 40 ? "HIGH" : "MEDIUM",
                List.of("PromotionReadinessService")));
    }

    @SuppressWarnings("unchecked")
    private void studyNext(UUID userId, List<Map<String, Object>> decisions, List<String> omitted) {
        if (!skillGap.isEnabled()) {
            omitted.add("STUDY_NEXT - SkillGapIntelligenceService disabled (career.skill-gap.enabled)");
            return;
        }
        Map<String, Object> gap = skillGap.compute(userId);
        List<String> priority = (List<String>) gap.getOrDefault("learningPriority", List.of());
        if (priority.isEmpty()) {
            omitted.add("STUDY_NEXT - no critical skill gaps identified yet");
            return;
        }
        String top = priority.get(0);
        int sampleSize = (int) gap.getOrDefault("sampleSize", 0);
        List<String> critical = (List<String>) gap.getOrDefault("criticalSkills", List.of());
        decisions.add(decision("STUDY_NEXT",
                "Learn " + top + " next",
                Map.of("skill", top, "sampleSizeJobsAnalyzed", sampleSize, "rankAmongCriticalSkills", critical.indexOf(top) + 1,
                        "totalCriticalSkills", critical.size()),
                sampleSize >= 10 ? "HIGH" : sampleSize > 0 ? "MEDIUM" : "LOW",
                List.of("SkillGapIntelligenceService")));
    }

    private void prepareInterview(UUID userId, List<Map<String, Object>> decisions, List<String> omitted) {
        List<Interview> allInterviews = interviews.findByUserIdOrderByCreatedAtDesc(userId);
        List<Interview> scheduled = allInterviews.stream()
                .filter(i -> Interview.RESULT_SCHEDULED.equals(i.getResult()))
                .toList();
        if (scheduled.isEmpty()) {
            omitted.add("PREPARE_INTERVIEW - no interviews with SCHEDULED status on file");
            return;
        }
        List<Map<String, Object>> items = scheduled.stream().map(i -> {
            List<Interview> sameType = allInterviews.stream()
                    .filter(x -> i.getInterviewType() != null && i.getInterviewType().equals(x.getInterviewType())
                            && (Interview.RESULT_PASSED.equals(x.getResult()) || Interview.RESULT_FAILED.equals(x.getResult())))
                    .toList();
            long passed = sameType.stream().filter(x -> Interview.RESULT_PASSED.equals(x.getResult())).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("interviewType", i.getInterviewType());
            m.put("scheduledAt", i.getScheduledAt());
            m.put("priorAttemptsOfThisType", sameType.size());
            m.put("historicPassRate", sameType.isEmpty() ? "NOT_COMPUTED - no prior interviews of this type" : Math.round(passed * 1000.0 / sameType.size()) / 10.0);
            return m;
        }).toList();

        decisions.add(decision("PREPARE_INTERVIEW",
                "Prepare for " + scheduled.size() + " upcoming interview(s)",
                Map.of("interviews", items),
                "HIGH",
                List.of("InterviewRepository")));
    }

    @SuppressWarnings("unchecked")
    private void switchGoal(CareerStrategy strategy, Map<String, Object> readinessByLevel, List<Map<String, Object>> decisions, List<String> omitted) {
        if (strategy == null || strategy.getCareerGoalJson() == null || strategy.getCareerGoalJson().isBlank()) {
            omitted.add("SWITCH_GOAL - no career goal has been set yet (POST /api/workflow/career-goal/plan)");
            return;
        }
        if (readinessByLevel.isEmpty()) {
            omitted.add("SWITCH_GOAL - PromotionReadinessService disabled or produced no readiness data");
            return;
        }
        Map<String, Object> goal;
        try {
            goal = mapper.readValue(strategy.getCareerGoalJson(), Map.class);
        } catch (Exception e) {
            omitted.add("SWITCH_GOAL - stored career goal could not be parsed");
            return;
        }
        String currentTargetLevel = (String) goal.get("targetLevel");
        if (currentTargetLevel == null) {
            omitted.add("SWITCH_GOAL - stored career goal has no targetLevel");
            return;
        }
        Integer currentScore = readinessScoreOf(readinessByLevel.get(currentTargetLevel));

        Optional<Map.Entry<String, Object>> better = readinessByLevel.entrySet().stream()
                .filter(e -> !e.getKey().equals(currentTargetLevel))
                .filter(e -> readinessScoreOf(e.getValue()) != null)
                .filter(e -> currentScore == null || readinessScoreOf(e.getValue()) >= currentScore + SWITCH_GOAL_READINESS_DELTA)
                .max(Comparator.comparingInt(e -> readinessScoreOf(e.getValue())));

        if (better.isEmpty()) {
            omitted.add("SWITCH_GOAL - no alternate level shows meaningfully higher readiness than the current goal");
            return;
        }
        decisions.add(decision("SWITCH_GOAL",
                "Consider switching your goal from " + currentTargetLevel + " to " + better.get().getKey(),
                Map.of("currentTargetLevel", currentTargetLevel, "currentReadiness", currentScore,
                        "alternateLevel", better.get().getKey(), "alternateReadiness", readinessScoreOf(better.get().getValue())),
                "MEDIUM",
                List.of("PromotionReadinessService", "CareerGoalPlannerService")));
    }

    private static Integer readinessScoreOf(Object levelEntry) {
        if (levelEntry instanceof Map<?, ?> m && m.get("readinessScore") instanceof Number n) return n.intValue();
        return null;
    }

    private static Map<String, Object> decision(String type, String recommendation, Map<String, Object> evidence,
                                                 String confidence, List<String> modulesUsed) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", type);
        d.put("recommendation", recommendation);
        d.put("evidence", evidence);
        d.put("confidence", confidence);
        d.put("modulesUsed", modulesUsed);
        d.put("timestamp", Instant.now());
        return d;
    }
}
