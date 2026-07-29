package ai.careerpilot.dailydiscovery;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.domain.CareerIntelligence;
import ai.careerpilot.domain.DailyCareerSummary;
import ai.careerpilot.domain.User;
import ai.careerpilot.learning.career.executive.ExecutiveDecisionEngine;
import ai.careerpilot.mission.MissionAwareDailyBriefService;
import ai.careerpilot.repo.CareerIntelligenceRepository;
import ai.careerpilot.repo.DailyCareerSummaryRepository;
import ai.careerpilot.service.profile.JsonLists;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5N — generates the short daily AI briefing text for one user from that user's
 * already-computed {@link DailyJobDiscoveryService.UserDiscoverySnapshot}. Uses the existing
 * {@link AiGatewayService} (same multi-provider failover chain as the rest of the backend) for
 * the natural-language wrapper only — every number quoted in the prompt/output comes from the
 * snapshot, never invented by the model. Falls back to a deterministic template (no AI call) if
 * the gateway throws, so a provider outage never blocks the pipeline.
 */
@Service
public class DailyCareerSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(DailyCareerSummaryGenerator.class);

    private final AiGatewayService ai;
    private final DailyCareerSummaryRepository summaries;
    private final CareerIntelligenceRepository careerIntelligence;
    private final ExecutiveDecisionEngine executiveDecisionEngine;
    private final MissionAwareDailyBriefService missionBrief;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public DailyCareerSummaryGenerator(AiGatewayService ai,
                                       DailyCareerSummaryRepository summaries,
                                       CareerIntelligenceRepository careerIntelligence,
                                       ExecutiveDecisionEngine executiveDecisionEngine,
                                       MissionAwareDailyBriefService missionBrief,
                                       @Value("${career.discovery.summary.enabled:false}") boolean enabled) {
        this.ai = ai;
        this.summaries = summaries;
        this.careerIntelligence = careerIntelligence;
        this.executiveDecisionEngine = executiveDecisionEngine;
        this.missionBrief = missionBrief;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /** Build and persist today's summary for one user. Never throws. */
    public void generate(UUID runId, User user, DailyJobDiscoveryService.UserDiscoverySnapshot s) {
        if (!enabled) return;
        try {
            BigDecimal interviewDelta = probabilityDelta(user.getId(), CareerIntelligence.DIM_INTERVIEW_PROBABILITY);
            BigDecimal offerDelta = probabilityDelta(user.getId(), CareerIntelligence.DIM_OFFER_PROBABILITY);
            String text = renderText(user, s, interviewDelta, offerDelta);
            Map<String, Object> decisions = executiveDecisionEngine.isEnabled() ? executiveDecisionEngine.decide(user.getId()) : null;

            DailyCareerSummary.DailyCareerSummaryBuilder builder = DailyCareerSummary.builder()
                    .runId(runId).userId(user.getId())
                    .summaryText(text)
                    .jobsFetched(s.recommendedJobs())
                    .jobsDeduped(s.recommendedJobs())
                    .recommendedCount(s.recommendedJobs())
                    .mustApplyCount(s.mustApplyJobs())
                    .highPriorityCount(s.highPriorityJobs())
                    .topCompanies(String.join(",", s.topCompanies()))
                    .topSkills(String.join(",", s.topSkills()))
                    .interviewProbabilityDelta(interviewDelta)
                    .offerProbabilityDelta(offerDelta)
                    .executiveDecisionsJson(executiveDecisionsJson(decisions))
                    .careerHealthScore(careerHealthScore(decisions));

            applyMissionBrief(builder, user.getId());

            summaries.save(builder.build());
        } catch (Exception e) {
            log.warn("Daily career summary generation failed for user={}: {}", user.getId(), e.toString());
        }
    }

    private String renderText(User user, DailyJobDiscoveryService.UserDiscoverySnapshot s,
                              BigDecimal interviewDelta, BigDecimal offerDelta) {
        String template = deterministicTemplate(user, s, interviewDelta, offerDelta);
        try {
            String system = """
                You are CareerPilot's daily briefing writer. Rewrite the following factual briefing
                into 4-6 short, warm sentences. Do NOT invent any number, company, or skill that is
                not already present in the input — only rephrase and summarize what is given.
                """;
            String out = ai.chat(List.of(new ChatMessage("user", template)), system);
            return (out == null || out.isBlank()) ? template : out;
        } catch (Exception e) {
            log.warn("AI gateway failed for daily summary (falling back to template): {}", e.toString());
            return template;
        }
    }

    private static String deterministicTemplate(User user, DailyJobDiscoveryService.UserDiscoverySnapshot s,
                                                BigDecimal interviewDelta, BigDecimal offerDelta) {
        String name = (user.getFullName() == null || user.getFullName().isBlank())
                ? "there" : user.getFullName().split(" ")[0];
        StringBuilder sb = new StringBuilder();
        sb.append("Good morning ").append(name).append(".\n\n");
        sb.append("Recommended jobs: ").append(s.recommendedJobs()).append("\n");
        sb.append("Must apply: ").append(s.mustApplyJobs()).append("\n");
        sb.append("High priority: ").append(s.highPriorityJobs()).append("\n");
        sb.append("Human review: ").append(s.humanReviewJobs()).append("\n");
        if (!s.topCompanies().isEmpty()) sb.append("Top companies: ").append(String.join(", ", s.topCompanies())).append("\n");
        if (!s.topSkills().isEmpty()) sb.append("Top skills: ").append(String.join(", ", s.topSkills())).append("\n");
        if (interviewDelta != null) sb.append("Interview probability change: ").append(interviewDelta).append("%\n");
        if (offerDelta != null) sb.append("Offer probability change: ").append(offerDelta).append("%\n");
        return sb.toString();
    }

    /**
     * Phase 6A — Mission-Aware Daily Coach Integration: attaches {@link MissionAwareDailyBriefService}'s
     * output to the daily brief, per explicit scope choice (extend this generator's own builder
     * rather than build a rival Daily Coach). No-op (builder untouched, every new column stays
     * null) when the flag is off or the user has no ACTIVE mission — {@link
     * MissionAwareDailyBriefService#buildFor} itself never throws, but this call site is
     * defensively wrapped too so a summary is still written even if that changes later.
     */
    private void applyMissionBrief(DailyCareerSummary.DailyCareerSummaryBuilder builder, UUID userId) {
        try {
            missionBrief.buildFor(userId).ifPresent(b -> builder
                    .missionId(b.missionId())
                    .missionName(b.missionName())
                    .missionProgressPercent(b.progressPercent())
                    .currentStrategyCountry(b.currentStrategyCountry())
                    .alternativeStrategyCountry(b.alternativeStrategyCountry())
                    .todaysMissionTasksJson(JsonLists.toJson(b.todaysMissionTasks()))
                    .priorityWorkflowsJson(JsonLists.toJson(b.priorityWorkflows()))
                    .highRiskAreasJson(JsonLists.toJson(b.highRiskAreas()))
                    .recommendedLearningJson(JsonLists.toJson(b.recommendedLearning()))
                    .recommendedJobsJson(JsonLists.toJson(b.recommendedJobs()))
                    .recommendedInterviewsJson(JsonLists.toJson(b.recommendedInterviews()))
                    .estimatedCompletionTimeline(b.estimatedCompletionTimeline())
                    .missionRecommendation(b.recommendation()));
        } catch (Exception e) {
            log.warn("Mission-aware brief application failed for user={}: {}", userId, e.toString());
        }
    }

    /**
     * Phase 7.19.5 — attaches the Executive Decision Engine's decision list to the daily brief,
     * per explicit scope choice (extend this generator rather than build a rival brief pipeline).
     * Null-safe: returns null when the engine is disabled, so the column stays empty (dark-ship).
     */
    private String executiveDecisionsJson(Map<String, Object> decisions) {
        if (decisions == null) return null;
        try {
            return mapper.writeValueAsString(decisions);
        } catch (Exception e) {
            log.warn("Failed to serialize executive decisions: {}", e.toString());
            return null;
        }
    }

    private static BigDecimal careerHealthScore(Map<String, Object> decisions) {
        if (decisions == null) return null;
        Object health = decisions.get("careerHealth");
        if (health instanceof Map<?, ?> m && m.get("value") instanceof BigDecimal bd) return bd;
        return null;
    }

    /** Delta vs. the prior stored probability for this dimension, or null if fewer than 2 samples exist. */
    private BigDecimal probabilityDelta(UUID userId, String dimension) {
        List<CareerIntelligence> rows = careerIntelligence.findByUserIdAndDimensionOrderByComputedAtDesc(userId, dimension);
        if (rows.size() < 2) return null;
        BigDecimal latest = rows.get(0).getProbability();
        BigDecimal prior = rows.get(1).getProbability();
        if (latest == null || prior == null) return null;
        return latest.subtract(prior).multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
