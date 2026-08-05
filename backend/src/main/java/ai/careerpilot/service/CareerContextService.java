package ai.careerpilot.service;

import ai.careerpilot.careertimeline.CareerTimelineService;
import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.mission.MissionOrchestratorService;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.submission.ApplicationSubmissionSessionService;
import ai.careerpilot.workflow.analytics.ApplicationAnalyticsService;
import ai.careerpilot.workflow.career.CareerIntelligenceService;
import ai.careerpilot.workflow.interview.InterviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 11A — the single, additive aggregator that gives the Copilot cross-cutting awareness of
 * subsystems it previously had zero access to: Mission, Career Timeline, Workflow lifecycle,
 * Applications (including the Phase-2E {@code WAITING_MANUAL_SUBMISSION} state), Interviews, and
 * Company Intelligence. It reuses every underlying repository/service exactly as-is (no duplicate
 * queries, no new tables, no new AI calls) — this class is pure read-side composition, the same
 * role {@link CareerContextRetriever} already plays for resume/job/application/workflow single-
 * entity lookups, just aggregated across subsystems instead of resolved by id.
 *
 * <p>Each source is independently try/catch isolated (same discipline as {@link
 * CareerTimelineService}'s per-source {@code safely(...)} helper) so one missing/misconfigured
 * subsystem degrades that one section to {@code null}/empty rather than losing the whole context.
 * A source gated by its own feature flag (Career Memory, Career Timeline, Interview Tracking,
 * Company Knowledge, Workflow/Career Analytics) is consulted through that subsystem's own {@code
 * isEnabled()}/dark-by-default read path — this class never bypasses those flags, and never
 * fabricates a value when a source is off or empty.
 *
 * <p>Phase 11B adds {@code recommendedActions}: {@link RecommendedActionEngine} derives a
 * deterministic, priority-ordered action list purely from the sections already fetched above —
 * no new query, no LLM call, no fabricated ranking.
 */
@Service
public class CareerContextService {

    private static final Logger log = LoggerFactory.getLogger(CareerContextService.class);
    private static final int TIMELINE_HIGHLIGHT_LIMIT = 5;
    private static final int TOP_COMPANIES_LIMIT = 3;

    private final CareerMissionRepository missions;
    private final MissionOrchestratorService missionOrchestrator;
    private final CareerTimelineService careerTimeline;
    private final WorkflowRunRepository workflowRuns;
    private final WorkflowService workflowService;
    private final ApplicationRepository applications;
    private final ObjectProvider<ApplicationSubmissionSessionService> submissionServiceProvider;
    private final ObjectProvider<InterviewService> interviewServiceProvider;
    private final InterviewRepository interviews;
    private final ObjectProvider<CompanyKnowledgeService> companyKnowledgeServiceProvider;
    private final CompanyKnowledgeRepository companyKnowledge;
    private final ObjectProvider<ApplicationAnalyticsService> applicationAnalyticsProvider;
    private final ObjectProvider<CareerIntelligenceService> careerIntelligenceProvider;
    private final RecommendedActionEngine recommendedActionEngine;

    public CareerContextService(CareerMissionRepository missions,
                                 MissionOrchestratorService missionOrchestrator,
                                 CareerTimelineService careerTimeline,
                                 WorkflowRunRepository workflowRuns,
                                 WorkflowService workflowService,
                                 ApplicationRepository applications,
                                 ObjectProvider<ApplicationSubmissionSessionService> submissionServiceProvider,
                                 ObjectProvider<InterviewService> interviewServiceProvider,
                                 InterviewRepository interviews,
                                 ObjectProvider<CompanyKnowledgeService> companyKnowledgeServiceProvider,
                                 CompanyKnowledgeRepository companyKnowledge,
                                 ObjectProvider<ApplicationAnalyticsService> applicationAnalyticsProvider,
                                 ObjectProvider<CareerIntelligenceService> careerIntelligenceProvider,
                                 RecommendedActionEngine recommendedActionEngine) {
        this.missions = missions;
        this.missionOrchestrator = missionOrchestrator;
        this.careerTimeline = careerTimeline;
        this.workflowRuns = workflowRuns;
        this.workflowService = workflowService;
        this.applications = applications;
        this.submissionServiceProvider = submissionServiceProvider;
        this.interviewServiceProvider = interviewServiceProvider;
        this.interviews = interviews;
        this.companyKnowledgeServiceProvider = companyKnowledgeServiceProvider;
        this.companyKnowledge = companyKnowledge;
        this.applicationAnalyticsProvider = applicationAnalyticsProvider;
        this.careerIntelligenceProvider = careerIntelligenceProvider;
        this.recommendedActionEngine = recommendedActionEngine;
    }

    /** One cross-subsystem snapshot for a single Copilot turn. Every field is nullable/empty-safe. */
    public record CareerContext(
            MissionSummary mission,
            List<TimelineHighlight> recentTimeline,
            WorkflowSummary workflow,
            ApplicationsSummary applications,
            InterviewSummary interviews,
            List<CompanySummary> topCompanies,
            String analyticsNote,
            List<RecommendedActionEngine.RecommendedAction> recommendedActions) {}

    public record MissionSummary(
            String targetRole,
            String targetLevel,
            String status,
            Integer timelineMonths,
            List<MissionOrchestratorService.Decision> recommendedNext) {}

    public record TimelineHighlight(String category, String title, java.time.Instant occurredAt) {}

    public record WorkflowSummary(
            String latestThreadId,
            String latestStatus,
            long runningCount,
            long failedCount,
            long interruptedCount) {}

    public record ApplicationsSummary(
            int total,
            Map<String, Long> countByStatus,
            int waitingManualSubmission) {}

    public record InterviewSummary(
            int total,
            long passed,
            long failed,
            String latestType,
            java.time.Instant latestScheduledAt) {}

    public record CompanySummary(
            String companyName,
            Integer hiringProbability,
            Integer technologyMatch,
            Integer interviewDifficulty) {}

    @Transactional(readOnly = true)
    public CareerContext getCareerContext(AuthenticatedUser user) {
        UUID userId = user.userId();
        MissionSummary mission = safely("mission", () -> missionSummary(userId));
        List<TimelineHighlight> timeline = safely("timeline", () -> timelineHighlights(userId));
        WorkflowSummary workflow = safely("workflow", () -> workflowSummary(userId));
        ApplicationsSummary applicationsSummary = safely("applications", () -> applicationsSummary(userId));
        InterviewSummary interviewSummary = safely("interviews", () -> interviewSummary(userId));
        List<CompanySummary> companies = safely("companies", () -> topCompanies(userId));
        String analytics = safely("analytics", () -> analyticsNote(userId));

        CareerContext partial = new CareerContext(mission, timeline, workflow, applicationsSummary,
                interviewSummary, companies, analytics, List.of());
        // Phase 11B — derived entirely from the sections already fetched above, zero extra queries.
        List<RecommendedActionEngine.RecommendedAction> actions =
                safely("recommended-actions", () -> recommendedActionEngine.derive(partial));

        return new CareerContext(mission, timeline, workflow, applicationsSummary, interviewSummary,
                companies, analytics, actions != null ? actions : List.of());
    }

    private MissionSummary missionSummary(UUID userId) {
        CareerMission mission = missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                        userId, ai.careerpilot.mission.MissionStatus.ACTIVE)
                .orElse(null);
        if (mission == null) return null;
        List<MissionOrchestratorService.Decision> decisions = missionOrchestrator.status(userId, mission.getId())
                .map(MissionOrchestratorService.OrchestrationResult::decisions)
                .orElse(List.of());
        return new MissionSummary(mission.getTargetRole(), mission.getTargetLevel(),
                mission.getStatus() != null ? mission.getStatus().name() : null,
                mission.getTimelineMonths(), decisions);
    }

    private List<TimelineHighlight> timelineHighlights(UUID userId) {
        if (!careerTimeline.isEnabled()) return List.of();
        CareerTimelineService.Page page = careerTimeline.forUser(userId, null, 0, TIMELINE_HIGHLIGHT_LIMIT);
        return page.entries().stream()
                .map(e -> new TimelineHighlight(e.category().name(), e.title(), e.occurredAt()))
                .toList();
    }

    private WorkflowSummary workflowSummary(UUID userId) {
        List<WorkflowRun> runs = workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId);
        if (runs.isEmpty()) return null;
        Map<WorkflowRun, String> derived = runs.stream()
                .collect(Collectors.toMap(r -> r, workflowService::deriveDisplayStatus));
        WorkflowRun latest = runs.get(0);
        long running = derived.values().stream().filter("RUNNING"::equals).count();
        long failed = derived.values().stream().filter("FAILED"::equals).count();
        long interrupted = derived.values().stream().filter("INTERRUPTED"::equals).count();
        return new WorkflowSummary(latest.getThreadId(), derived.get(latest), running, failed, interrupted);
    }

    private ApplicationsSummary applicationsSummary(UUID userId) {
        List<Application> apps = applications.findByUserIdOrderByCreatedAtDesc(userId);
        if (apps.isEmpty()) return null;
        Map<String, Long> byStatus = apps.stream()
                .collect(Collectors.groupingBy(Application::getStatus, Collectors.counting()));

        int waitingManualSubmission = 0;
        ApplicationSubmissionSessionService submissionService = submissionServiceProvider.getIfAvailable();
        if (submissionService != null && submissionService.isEnabled()) {
            waitingManualSubmission = (int) submissionService.recentForUser(userId).stream()
                    .filter(s -> ai.careerpilot.domain.ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION
                            .equals(s.getStatus()))
                    .count();
        }
        return new ApplicationsSummary(apps.size(), byStatus, waitingManualSubmission);
    }

    private InterviewSummary interviewSummary(UUID userId) {
        InterviewService svc = interviewServiceProvider.getIfAvailable();
        if (svc == null || !svc.isEnabled()) return null;
        List<Interview> rows = interviews.findByUserIdOrderByCreatedAtDesc(userId);
        if (rows.isEmpty()) return null;
        long passed = rows.stream().filter(i -> Interview.RESULT_PASSED.equals(i.getResult())).count();
        long failed = rows.stream().filter(i -> Interview.RESULT_FAILED.equals(i.getResult())).count();
        Interview latest = rows.get(0);
        return new InterviewSummary(rows.size(), passed, failed, latest.getInterviewType(), latest.getScheduledAt());
    }

    private List<CompanySummary> topCompanies(UUID userId) {
        CompanyKnowledgeService svc = companyKnowledgeServiceProvider.getIfAvailable();
        if (svc == null || !svc.isEnabled()) return List.of();
        List<CompanyKnowledge> rows = companyKnowledge.findByUserIdOrderByUpdatedAtDesc(userId);
        return rows.stream()
                .sorted(Comparator.comparing(
                        (CompanyKnowledge c) -> c.getHiringProbability() != null ? c.getHiringProbability() : -1)
                        .reversed())
                .limit(TOP_COMPANIES_LIMIT)
                .map(c -> new CompanySummary(c.getCompanyName(), c.getHiringProbability(),
                        c.getTechnologyMatch(), c.getInterviewDifficulty()))
                .toList();
    }

    /**
     * {@code ApplicationAnalytics}/{@code CareerIntelligence} are append-only numeric snapshot
     * series with no discrete "score improved" event ever computed anywhere in this codebase (see
     * CLAUDE.md's Career Timeline section) — synthesizing a trend narrative here would be exactly
     * the fabrication this platform's own discipline forbids. This returns an honest note instead:
     * a real snapshot count when one exists, or an explicit "no verified trend" statement.
     */
    private String analyticsNote(UUID userId) {
        ApplicationAnalyticsService appAnalytics = applicationAnalyticsProvider.getIfAvailable();
        CareerIntelligenceService careerIntel = careerIntelligenceProvider.getIfAvailable();
        int appSnapshots = (appAnalytics != null && appAnalytics.isEnabled())
                ? appAnalytics.forUser(userId).size() : 0;
        int careerSnapshots = (careerIntel != null && careerIntel.isEnabled())
                ? careerIntel.forUser(userId).size() : 0;
        if (appSnapshots == 0 && careerSnapshots == 0) {
            return "No verified historical trend available.";
        }
        StringBuilder sb = new StringBuilder();
        if (appSnapshots > 0) {
            sb.append(appSnapshots).append(" application-outcome metric snapshot(s) recorded.");
        }
        if (careerSnapshots > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(careerSnapshots).append(" career-probability dimension snapshot(s) recorded.");
        }
        return sb.toString();
    }

    private interface Producer<T> {
        T get();
    }

    private <T> T safely(String source, Producer<T> producer) {
        try {
            return producer.get();
        } catch (Exception e) {
            log.debug("Career context source '{}' unavailable: {}", source, e.toString());
            return null;
        }
    }
}
