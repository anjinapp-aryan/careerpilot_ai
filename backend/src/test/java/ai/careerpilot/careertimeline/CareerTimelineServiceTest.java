package ai.careerpilot.careertimeline;

import ai.careerpilot.domain.ApplicationTimeline;
import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyTimelineEvent;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.repo.ApplicationTimelineRepository;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.CompanyTimelineEventRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CareerTimelineServiceTest {

    private final CareerMissionRepository missions = mock(CareerMissionRepository.class);
    private final ApplicationTimelineRepository applicationTimelines = mock(ApplicationTimelineRepository.class);
    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final ResumeTailoringRepository resumeTailorings = mock(ResumeTailoringRepository.class);
    private final LearningEventRepository learningEvents = mock(LearningEventRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final CareerMemoryService careerMemory = mock(CareerMemoryService.class);
    private final CompanyKnowledgeRepository companyKnowledge = mock(CompanyKnowledgeRepository.class);
    private final CompanyTimelineEventRepository companyTimelineEvents = mock(CompanyTimelineEventRepository.class);
    private final WorkflowRunRepository workflowRuns = mock(WorkflowRunRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        when(missions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(applicationTimelines.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(resumeTailorings.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(learningEvents.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(careerMemory.timelineFor(userId)).thenReturn(List.of());
        when(companyKnowledge.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        when(companyTimelineEvents.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
    }

    private CareerTimelineService service(boolean enabled) {
        return new CareerTimelineService(missions, applicationTimelines, resumes, resumeTailorings, learningEvents,
                interviews, careerMemory, companyKnowledge, companyTimelineEvents, workflowRuns, enabled);
    }

    @Test
    void disabledReturnsEmptyPageAndTouchesNoRepository() {
        CareerTimelineService.Page result = service(false).forUser(userId, null, 0, 25);
        assertThat(result.entries()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        verifyNoInteractions(missions, applicationTimelines, resumes, resumeTailorings, learningEvents,
                interviews, careerMemory, companyKnowledge, companyTimelineEvents, workflowRuns);
    }

    @Test
    void emptyEverywhereReturnsEmptyNotError() {
        CareerTimelineService.Page result = service(true).forUser(userId, null, 0, 25);
        assertThat(result.entries()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void missionCreatedAndCompletedBothAppearWhenTimestampsDiffer() {
        CareerMission m = CareerMission.builder().id(UUID.randomUUID()).userId(userId)
                .missionStatement("Become Principal Engineer").targetRole("Principal Engineer")
                .status(MissionStatus.COMPLETED)
                .createdAt(now.minus(10, ChronoUnit.DAYS)).updatedAt(now).build();
        when(missions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(m));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, CareerTimelineCategory.MISSION, 0, 25).entries();

        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> assertThat(e.title()).isEqualTo("Mission created"));
        assertThat(entries).anySatisfy(e -> assertThat(e.title()).isEqualTo("Mission completed"));
        assertThat(entries).allSatisfy(e -> assertThat(e.category()).isEqualTo(CareerTimelineCategory.MISSION));
    }

    @Test
    void activeMissionWithUnchangedTimestampsOnlyEmitsCreated() {
        CareerMission m = CareerMission.builder().id(UUID.randomUUID()).userId(userId)
                .missionStatement("Become Staff Engineer").targetRole("Staff Engineer")
                .status(MissionStatus.ACTIVE)
                .createdAt(now).updatedAt(now).build();
        when(missions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(m));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, CareerTimelineCategory.MISSION, 0, 25).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).title()).isEqualTo("Mission created");
    }

    @Test
    void applicationEventsPassThroughVerbatim() {
        ApplicationTimeline t = ApplicationTimeline.builder().id(UUID.randomUUID()).userId(userId)
                .jobId(UUID.randomUUID()).eventType("INTERVIEW_SCHEDULED").eventSource("EMAIL")
                .details("Recruiter call scheduled").occurredAt(now).build();
        when(applicationTimelines.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(t));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, CareerTimelineCategory.APPLICATION, 0, 25).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).title()).isEqualTo("Interview scheduled");
        assertThat(entries.get(0).description()).isEqualTo("Recruiter call scheduled");
        assertThat(entries.get(0).relatedJobId()).isEqualTo(t.getJobId());
    }

    @Test
    void memoryEntriesReuseCareerMemoryServiceVerbatim() {
        CareerDecisionMemory mem = CareerDecisionMemory.builder().id(UUID.randomUUID()).userId(userId)
                .decisionType("APPLICATION_REJECTED").category("APPLICATION").reason("Not enough experience")
                .source("EVENT_BRIDGE").aiGenerated(true).createdAt(now).build();
        when(careerMemory.timelineFor(userId)).thenReturn(List.of(mem));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, CareerTimelineCategory.MEMORY, 0, 25).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).eventType()).isEqualTo("APPLICATION_REJECTED");
        assertThat(entries.get(0).aiGenerated()).isTrue();
        verify(careerMemory, never()).recordUsage(any());
    }

    @Test
    void companyEventsResolveCompanyNameFromKnowledgeLookup() {
        UUID companyId = UUID.randomUUID();
        CompanyKnowledge ck = CompanyKnowledge.builder().id(companyId).userId(userId).companyName("Acme Corp").build();
        CompanyTimelineEvent evt = CompanyTimelineEvent.builder().id(UUID.randomUUID()).userId(userId)
                .companyKnowledgeId(companyId).eventType("INTERVIEW_SCHEDULED").occurredAt(now).build();
        when(companyKnowledge.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(ck));
        when(companyTimelineEvents.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(evt));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, CareerTimelineCategory.COMPANY, 0, 25).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).relatedCompanyName()).isEqualTo("Acme Corp");
        assertThat(entries.get(0).title()).contains("Acme Corp");
    }

    @Test
    void workflowStartedAndTerminalBothAppearWhenTimestampsDiffer() {
        WorkflowRun run = WorkflowRun.builder().id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID())
                .threadId("thread-1").status("COMPLETED").targetRole("Senior Engineer").state("{}")
                .createdAt(now.minus(1, ChronoUnit.HOURS)).updatedAt(now).build();
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, CareerTimelineCategory.WORKFLOW, 0, 25).entries();

        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> assertThat(e.title()).isEqualTo("AI workflow started for Senior Engineer"));
        assertThat(entries).anySatisfy(e -> assertThat(e.title()).isEqualTo("AI workflow completed"));
    }

    @Test
    void oneSourceThrowingDegradesToEmptyWithoutBreakingOthers() {
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenThrow(new RuntimeException("db down"));
        ApplicationTimeline t = ApplicationTimeline.builder().id(UUID.randomUUID()).userId(userId)
                .jobId(UUID.randomUUID()).eventType("APPLICATION_STARTED").occurredAt(now).build();
        when(applicationTimelines.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(t));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, null, 0, 25).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).category()).isEqualTo(CareerTimelineCategory.APPLICATION);
    }

    @Test
    void categoryFilterRestrictsToOneSource() {
        CareerMission m = CareerMission.builder().id(UUID.randomUUID()).userId(userId)
                .missionStatement("x").targetRole("x").status(MissionStatus.ACTIVE).createdAt(now).updatedAt(now).build();
        ApplicationTimeline t = ApplicationTimeline.builder().id(UUID.randomUUID()).userId(userId)
                .jobId(UUID.randomUUID()).eventType("APPLICATION_STARTED").occurredAt(now).build();
        when(missions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(m));
        when(applicationTimelines.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(t));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, CareerTimelineCategory.APPLICATION, 0, 25).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).category()).isEqualTo(CareerTimelineCategory.APPLICATION);
    }

    @Test
    void resultsAreSortedNewestFirstAcrossCategories() {
        CareerMission oldMission = CareerMission.builder().id(UUID.randomUUID()).userId(userId)
                .missionStatement("x").targetRole("x").status(MissionStatus.ACTIVE)
                .createdAt(now.minus(5, ChronoUnit.DAYS)).updatedAt(now.minus(5, ChronoUnit.DAYS)).build();
        ApplicationTimeline recentApp = ApplicationTimeline.builder().id(UUID.randomUUID()).userId(userId)
                .jobId(UUID.randomUUID()).eventType("APPLICATION_STARTED").occurredAt(now).build();
        when(missions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(oldMission));
        when(applicationTimelines.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(recentApp));

        List<CareerTimelineEntry> entries = service(true).forUser(userId, null, 0, 25).entries();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).category()).isEqualTo(CareerTimelineCategory.APPLICATION);
        assertThat(entries.get(1).category()).isEqualTo(CareerTimelineCategory.MISSION);
    }

    @Test
    void paginationReturnsHasMoreTrueWhenExtraRowExists() {
        List<CareerDecisionMemory> mems = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mems.add(CareerDecisionMemory.builder().id(UUID.randomUUID()).userId(userId)
                    .decisionType("JOB_APPROVED").source("FEEDBACK").createdAt(now.minus(i, ChronoUnit.HOURS)).build());
        }
        when(careerMemory.timelineFor(userId)).thenReturn(mems);

        CareerTimelineService.Page page1 = service(true).forUser(userId, CareerTimelineCategory.MEMORY, 0, 2);
        assertThat(page1.entries()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();

        CareerTimelineService.Page lastPage = service(true).forUser(userId, CareerTimelineCategory.MEMORY, 2, 2);
        assertThat(lastPage.entries()).hasSize(1);
        assertThat(lastPage.hasMore()).isFalse();
    }
}
