package ai.careerpilot.service;

import ai.careerpilot.careertimeline.CareerTimelineService;
import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.mission.MissionOrchestratorService;
import ai.careerpilot.mission.MissionStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 11A — verifies {@link CareerContextService} degrades every section independently: an
 * absent/disabled subsystem yields {@code null}/empty for that section only, never an exception,
 * never a fabricated value, and never affects the other sections.
 */
class CareerContextServiceTest {

    private final CareerMissionRepository missions = mock(CareerMissionRepository.class);
    private final MissionOrchestratorService missionOrchestrator = mock(MissionOrchestratorService.class);
    private final CareerTimelineService careerTimeline = mock(CareerTimelineService.class);
    private final WorkflowRunRepository workflowRuns = mock(WorkflowRunRepository.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final CompanyKnowledgeRepository companyKnowledge = mock(CompanyKnowledgeRepository.class);
    private final RecommendedActionEngine recommendedActionEngine = new RecommendedActionEngine();

    private final UUID userId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerFor(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private CareerContextService service(ObjectProvider<ApplicationSubmissionSessionService> submissionProvider,
                                          ObjectProvider<InterviewService> interviewServiceProvider,
                                          ObjectProvider<CompanyKnowledgeService> companyKnowledgeServiceProvider,
                                          ObjectProvider<ApplicationAnalyticsService> appAnalyticsProvider,
                                          ObjectProvider<CareerIntelligenceService> careerIntelProvider) {
        return new CareerContextService(missions, missionOrchestrator, careerTimeline, workflowRuns, workflowService,
                applications, submissionProvider, interviewServiceProvider, interviews,
                companyKnowledgeServiceProvider, companyKnowledge, appAnalyticsProvider, careerIntelProvider,
                recommendedActionEngine);
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(userId, UUID.randomUUID(), "u@example.com", "USER");
    }

    @BeforeEach
    void setUp() {
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(careerTimeline.isEnabled()).thenReturn(false);
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
    }

    @Test
    void everythingAbsentOrDisabled_returnsAllNullEmptyNeverThrows() {
        CareerContextService svc = service(providerFor(null), providerFor(null), providerFor(null),
                providerFor(null), providerFor(null));

        CareerContextService.CareerContext ctx = svc.getCareerContext(user());

        assertThat(ctx.mission()).isNull();
        assertThat(ctx.recentTimeline()).isEmpty();
        assertThat(ctx.workflow()).isNull();
        assertThat(ctx.applications()).isNull();
        assertThat(ctx.interviews()).isNull();
        assertThat(ctx.topCompanies()).isEmpty();
        assertThat(ctx.analyticsNote()).isEqualTo("No verified historical trend available.");
        assertThat(ctx.recommendedActions()).isEmpty();
    }

    @Test
    void recommendedActions_derivedFromSameAggregationCallNoExtraQueries() {
        ApplicationSubmissionSessionService submissionService = mock(ApplicationSubmissionSessionService.class);
        when(submissionService.isEnabled()).thenReturn(true);
        ApplicationSubmissionSession waiting = new ApplicationSubmissionSession();
        waiting.setStatus(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
        when(submissionService.recentForUser(userId)).thenReturn(List.of(waiting));

        Application app = new Application();
        app.setStatus("APPLIED");
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(app));

        CareerContextService svc = service(providerFor(submissionService), providerFor(null), providerFor(null),
                providerFor(null), providerFor(null));

        List<RecommendedActionEngine.RecommendedAction> actions = svc.getCareerContext(user()).recommendedActions();

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).category()).isEqualTo("Applications");
        // Exactly one call each — proves recommendedActions was derived from the already-fetched
        // sections rather than re-querying.
        verify(applications, times(1)).findByUserIdOrderByCreatedAtDesc(userId);
        verify(submissionService, times(1)).recentForUser(userId);
    }

    @Test
    void activeMission_includesOrchestratorRecommendations() {
        CareerMission mission = CareerMission.builder()
                .id(UUID.randomUUID()).userId(userId).targetRole("Principal Engineer")
                .targetLevel("PRINCIPAL").timelineMonths(24).status(MissionStatus.ACTIVE).build();
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE))
                .thenReturn(Optional.of(mission));
        MissionOrchestratorService.Decision decision =
                new MissionOrchestratorService.Decision("SKILL_ANALYSIS_V1", "incomplete skill actions");
        when(missionOrchestrator.status(userId, mission.getId())).thenReturn(
                Optional.of(new MissionOrchestratorService.OrchestrationResult(null, List.of(decision))));

        CareerContextService svc = service(providerFor(null), providerFor(null), providerFor(null),
                providerFor(null), providerFor(null));
        CareerContextService.MissionSummary summary = svc.getCareerContext(user()).mission();

        assertThat(summary).isNotNull();
        assertThat(summary.targetRole()).isEqualTo("Principal Engineer");
        assertThat(summary.recommendedNext()).containsExactly(decision);
    }

    @Test
    void workflowRuns_deriveDisplayStatusUsedNotRawColumn() {
        WorkflowRun run = WorkflowRun.builder().threadId("t1").userId(userId).build();
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        when(workflowService.deriveDisplayStatus(run)).thenReturn("RUNNING");

        CareerContextService svc = service(providerFor(null), providerFor(null), providerFor(null),
                providerFor(null), providerFor(null));
        CareerContextService.WorkflowSummary summary = svc.getCareerContext(user()).workflow();

        assertThat(summary).isNotNull();
        assertThat(summary.latestStatus()).isEqualTo("RUNNING");
        assertThat(summary.runningCount()).isEqualTo(1);
    }

    @Test
    void applications_countsWaitingManualSubmissionOnlyWhenSubmissionServiceEnabled() {
        Application app = new Application();
        app.setStatus("APPLIED");
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(app));

        ApplicationSubmissionSessionService submissionService = mock(ApplicationSubmissionSessionService.class);
        when(submissionService.isEnabled()).thenReturn(true);
        ApplicationSubmissionSession waiting = new ApplicationSubmissionSession();
        waiting.setStatus(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
        when(submissionService.recentForUser(userId)).thenReturn(List.of(waiting));

        CareerContextService svc = service(providerFor(submissionService), providerFor(null), providerFor(null),
                providerFor(null), providerFor(null));
        CareerContextService.ApplicationsSummary summary = svc.getCareerContext(user()).applications();

        assertThat(summary).isNotNull();
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.waitingManualSubmission()).isEqualTo(1);
    }

    @Test
    void interviews_nullWhenInterviewServiceDisabled_evenIfRowsExist() {
        InterviewService interviewService = mock(InterviewService.class);
        when(interviewService.isEnabled()).thenReturn(false);
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(
                List.of(Interview.builder().interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_PASSED).build()));

        CareerContextService svc = service(providerFor(null), providerFor(interviewService), providerFor(null),
                providerFor(null), providerFor(null));

        assertThat(svc.getCareerContext(user()).interviews()).isNull();
    }

    @Test
    void interviews_populatedWhenEnabled() {
        InterviewService interviewService = mock(InterviewService.class);
        when(interviewService.isEnabled()).thenReturn(true);
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                Interview.builder().interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_PASSED).build(),
                Interview.builder().interviewType(Interview.TYPE_RECRUITER).result(Interview.RESULT_FAILED).build()));

        CareerContextService svc = service(providerFor(null), providerFor(interviewService), providerFor(null),
                providerFor(null), providerFor(null));
        CareerContextService.InterviewSummary summary = svc.getCareerContext(user()).interviews();

        assertThat(summary).isNotNull();
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.passed()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    void topCompanies_emptyWhenCompanyKnowledgeDisabled() {
        CompanyKnowledgeService companyService = mock(CompanyKnowledgeService.class);
        when(companyService.isEnabled()).thenReturn(false);
        when(companyKnowledge.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(
                List.of(CompanyKnowledge.builder().companyName("Acme").hiringProbability(80).build()));

        CareerContextService svc = service(providerFor(null), providerFor(null), providerFor(companyService),
                providerFor(null), providerFor(null));

        assertThat(svc.getCareerContext(user()).topCompanies()).isEmpty();
    }

    @Test
    void topCompanies_rankedByHiringProbabilityWhenEnabled() {
        CompanyKnowledgeService companyService = mock(CompanyKnowledgeService.class);
        when(companyService.isEnabled()).thenReturn(true);
        when(companyKnowledge.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(
                CompanyKnowledge.builder().companyName("Low").hiringProbability(20).build(),
                CompanyKnowledge.builder().companyName("High").hiringProbability(90).build()));

        CareerContextService svc = service(providerFor(null), providerFor(null), providerFor(companyService),
                providerFor(null), providerFor(null));
        List<CareerContextService.CompanySummary> top = svc.getCareerContext(user()).topCompanies();

        assertThat(top).hasSize(2);
        assertThat(top.get(0).companyName()).isEqualTo("High");
    }

    @Test
    void sourceThrowing_isolatedFromOtherSections() {
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE))
                .thenThrow(new RuntimeException("db down"));
        WorkflowRun run = WorkflowRun.builder().threadId("t1").userId(userId).build();
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        when(workflowService.deriveDisplayStatus(run)).thenReturn("COMPLETED");

        CareerContextService svc = service(providerFor(null), providerFor(null), providerFor(null),
                providerFor(null), providerFor(null));
        CareerContextService.CareerContext ctx = svc.getCareerContext(user());

        assertThat(ctx.mission()).isNull();
        assertThat(ctx.workflow()).isNotNull();
        assertThat(ctx.workflow().latestStatus()).isEqualTo("COMPLETED");
    }
}
