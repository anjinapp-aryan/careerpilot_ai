package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.jobdiscovery.JobDiscoveryHealthTracker;
import ai.careerpilot.jobdiscovery.provider.JobProvider;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copilot skill: "how healthy is job discovery right now?" — surfaces the same per-provider
 * health data as {@code GET /api/diagnostics/job-providers} ({@link JobDiscoveryHealthTracker})
 * through the conversational Copilot surface, reusing the existing skill-routing infrastructure
 * (Copilot integration point requested for the two new providers + observability layer) rather
 * than building a parallel chat surface. Read-only: never triggers a discovery run.
 */
@Component
public class JobDiscoveryHealthHandler extends AbstractSkillHandler {

    private final JobDiscoveryHealthTracker health;
    private final List<JobProvider> providers;

    public JobDiscoveryHealthHandler(CareerContextRetriever retriever,
                                     JobDiscoveryHealthTracker health,
                                     List<JobProvider> providers) {
        super(retriever);
        this.health = health;
        this.providers = providers;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.JOB_DISCOVERY_HEALTH; }

    @Override
    public void assembleContext(SkillContext context) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (JobProvider p : providers) {
            JobDiscoveryHealthTracker.Snapshot s = health.snapshot(p.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configured", p.isConfigured());
            row.put("circuitState", s.circuitState());
            row.put("totalRuns", s.totalRuns());
            row.put("successRate", s.successRate());
            row.put("lastJobsFetched", s.lastJobsFetched());
            row.put("lastRunAt", s.lastRunAt());
            row.put("lastError", s.lastError());
            snapshot.put(p.name(), row);
        }
        context.jobDiscoveryHealth(snapshot);
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an AI assistant explaining the job-discovery ingestion
            pipeline's operational health to a user (typically an admin/operator).

            TASK — Explain Job Discovery Provider Health: The CONTEXT lists every registered job
            source provider (Greenhouse, Lever, Ashby, SmartRecruiters, RemoteOK, Arbeitnow, Adzuna,
            Jooble) with its configured state, circuit-breaker state (CLOSED/OPEN/HALF_OPEN), run
            counts, success rate, last job count fetched, and last error (if any).

            Explain, in plain language:
            - Which providers are actively contributing jobs vs. unconfigured (dark by design vs.
              a real problem).
            - Any provider with circuit state OPEN or a low success rate — flag it and summarize
              the last error.
            - Never fabricate numbers; if a provider has zero runs, say it simply hasn't executed
              yet (NOT that it is broken).
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        Map<String, Object> snapshot = context.jobDiscoveryHealth();
        if (snapshot == null || snapshot.isEmpty()) return "No job discovery provider data available.";

        StringBuilder sb = new StringBuilder("CONTEXT — Job Discovery Provider Health\n");
        for (Map.Entry<String, Object> e : snapshot.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) e.getValue();
            sb.append("- ").append(e.getKey()).append(": ")
              .append("configured=").append(row.get("configured"))
              .append(", circuit=").append(row.get("circuitState"))
              .append(", runs=").append(row.get("totalRuns"))
              .append(", successRate=").append(row.get("successRate"))
              .append(", lastFetched=").append(row.get("lastJobsFetched"))
              .append(", lastRunAt=").append(row.get("lastRunAt"));
            Object lastError = row.get("lastError");
            if (lastError != null) sb.append(", lastError=").append(lastError);
            sb.append("\n");
        }
        return sb.toString();
    }
}
