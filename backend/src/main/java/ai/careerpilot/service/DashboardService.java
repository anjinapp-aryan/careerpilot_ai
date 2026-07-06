package ai.careerpilot.service;

import ai.careerpilot.domain.DailyCareerSummary;
import ai.careerpilot.domain.DailyDiscoveryAnalytics;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.DailyCareerSummaryRepository;
import ai.careerpilot.repo.DailyDiscoveryAnalyticsRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardService {

    private final WorkflowRunRepository runs;
    private final ApplicationRepository apps;
    private final ResumeRepository resumes;
    private final DailyDiscoveryAnalyticsRepository dailyAnalytics;
    private final DailyCareerSummaryRepository dailySummaries;

    public DashboardService(WorkflowRunRepository runs, ApplicationRepository apps, ResumeRepository resumes,
                            DailyDiscoveryAnalyticsRepository dailyAnalytics,
                            DailyCareerSummaryRepository dailySummaries) {
        this.runs = runs;
        this.apps = apps;
        this.resumes = resumes;
        this.dailyAnalytics = dailyAnalytics;
        this.dailySummaries = dailySummaries;
    }

    public Map<String, Object> snapshot(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        var latest = runs.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream().findFirst().orElse(null);

        int resumeScore = pick(latest, WorkflowRun::getResumeScore);
        int atsScore = pick(latest, WorkflowRun::getAtsScore);
        int matchScore = pick(latest, WorkflowRun::getJobMatchScore);
        int interviewScore = pick(latest, WorkflowRun::getInterviewReadinessScore);
        int careerHealth = (resumeScore + atsScore + matchScore + interviewScore) / 4;

        out.put("careerHealthScore", careerHealth);
        out.put("resumeScore", resumeScore);
        out.put("atsScore", atsScore);
        out.put("jobMatchScore", matchScore);
        out.put("interviewReadinessScore", interviewScore);
        out.put("offerProbabilityScore", Math.min(100, (matchScore + interviewScore) / 2));

        out.put("applications", Map.of(
                "saved", apps.countByUserIdAndStatus(userId, "SAVED"),
                "applied", apps.countByUserIdAndStatus(userId, "APPLIED"),
                "interviewing", apps.countByUserIdAndStatus(userId, "INTERVIEWING"),
                "offer", apps.countByUserIdAndStatus(userId, "OFFER"),
                "rejected", apps.countByUserIdAndStatus(userId, "REJECTED")));

        out.put("resumes", resumes.findByUserIdOrderByCreatedAtDesc(userId).size());
        out.put("recentRuns", runs.findTop20ByUserIdOrderByCreatedAtDesc(userId));
        out.put("dailyDiscovery", dailyDiscoverySnapshot(userId));
        return out;
    }

    /**
     * Phase 5O — additive dashboard section fed by the (dark-by-default) daily discovery agent.
     * Null when the agent has never run for this user, so the UI can render an empty state
     * instead of fabricating numbers.
     */
    private Map<String, Object> dailyDiscoverySnapshot(UUID userId) {
        DailyDiscoveryAnalytics latest = dailyAnalytics.findFirstByUserIdOrderByComputedAtDesc(userId).orElse(null);
        if (latest == null) return null;
        DailyCareerSummary summary = dailySummaries.findFirstByUserIdOrderByCreatedAtDesc(userId).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("computedAt", latest.getComputedAt());
        out.put("recommendedJobs", latest.getRecommendedJobs());
        out.put("mustApplyJobs", latest.getMustApplyJobs());
        out.put("highPriorityJobs", latest.getHighPriorityJobs());
        out.put("humanReviewJobs", latest.getHumanReviewJobs());
        out.put("domesticJobs", latest.getDomesticJobs());
        out.put("internationalJobs", latest.getInternationalJobs());
        out.put("averageScore", latest.getAverageScore());
        out.put("industryDistribution", latest.getIndustryDistribution());
        out.put("skillDistribution", latest.getSkillDistribution());
        out.put("companyDistribution", latest.getCompanyDistribution());
        out.put("matchStrengthDistribution", latest.getMatchStrengthDistribution());
        if (summary != null) {
            out.put("summaryText", summary.getSummaryText());
            out.put("topCompanies", summary.getTopCompanies());
            out.put("topSkills", summary.getTopSkills());
            out.put("interviewProbabilityDelta", summary.getInterviewProbabilityDelta());
            out.put("offerProbabilityDelta", summary.getOfferProbabilityDelta());
        }
        return out;
    }

    private int pick(WorkflowRun r, java.util.function.Function<WorkflowRun, Integer> f) {
        if (r == null) return 0;
        Integer v = f.apply(r);
        return v == null ? 0 : v;
    }
}
