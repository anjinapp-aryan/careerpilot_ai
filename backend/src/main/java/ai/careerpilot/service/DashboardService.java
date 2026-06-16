package ai.careerpilot.service;

import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.ApplicationRepository;
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

    public DashboardService(WorkflowRunRepository runs, ApplicationRepository apps, ResumeRepository resumes) {
        this.runs = runs;
        this.apps = apps;
        this.resumes = resumes;
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
        return out;
    }

    private int pick(WorkflowRun r, java.util.function.Function<WorkflowRun, Integer> f) {
        if (r == null) return 0;
        Integer v = f.apply(r);
        return v == null ? 0 : v;
    }
}
