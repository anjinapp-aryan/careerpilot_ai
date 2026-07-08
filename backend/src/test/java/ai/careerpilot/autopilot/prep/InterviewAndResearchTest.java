package ai.careerpilot.autopilot.prep;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.autopilot.calendar.CalendarIntelligenceService;
import ai.careerpilot.autopilot.calendar.CalendarIntelligenceService.CalendarStatus;
import ai.careerpilot.autopilot.research.CompanyResearchEngine;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Phase 7.7-7.9 — fail-safe behavior of the calendar, company-research, and interview-prep agents. */
class InterviewAndResearchTest {

    private AiGatewayService ai;
    private JobRepository jobs;
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ai = mock(AiGatewayService.class);
        jobs = mock(JobRepository.class);
    }

    private void stubJob() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(
                Job.builder().id(jobId).company("Acme").title("Backend Engineer").description("Java role").build()));
    }

    // ── 7.7 Calendar ──

    @Test
    void calendarDisabledReturnsDisabled() {
        var svc = new CalendarIntelligenceService(false);
        assertEquals(CalendarStatus.DISABLED, svc.scheduleInterviewPrep(userId, jobId, "TECHNICAL", null).status());
    }

    @Test
    void calendarEnabledNeverFabricatesAScheduledEvent() {
        var svc = new CalendarIntelligenceService(true);
        var r = svc.scheduleInterviewPrep(userId, jobId, "TECHNICAL", "2026-08-01T10:00:00Z");
        assertEquals(CalendarStatus.NOT_INTEGRATED, r.status());
        assertTrue(r.reason().toLowerCase().contains("manually"));
    }

    // ── 7.8 Company research ──

    @Test
    void companyResearchDisabledIsEmpty() {
        var engine = new CompanyResearchEngine(ai, jobs, false);
        assertTrue(engine.research(jobId).isEmpty());
        verifyNoInteractions(ai);
    }

    @Test
    void companyResearchReusesAiGatewayWhenEnabled() {
        stubJob();
        when(ai.chat(anyList(), anyString())).thenReturn("Acme builds payments infra.");
        var engine = new CompanyResearchEngine(ai, jobs, true);
        var out = engine.research(jobId);
        assertTrue(out.isPresent());
        assertEquals("Acme", out.get().company());
        assertTrue(out.get().summary().contains("payments"));
    }

    @Test
    void companyResearchLlmFailureIsEmptyNotFabricated() {
        stubJob();
        when(ai.chat(anyList(), anyString())).thenThrow(new RuntimeException("providers down"));
        assertTrue(new CompanyResearchEngine(ai, jobs, true).research(jobId).isEmpty());
    }

    // ── 7.9 Interview prep ──

    @Test
    void interviewPrepDisabledIsEmpty() {
        var research = new CompanyResearchEngine(ai, jobs, false);
        var svc = new InterviewPreparationService(ai, jobs, research, false);
        assertTrue(svc.prepare(userId, jobId).isEmpty());
        verifyNoInteractions(ai);
    }

    @Test
    void interviewPrepGeneratesPlanFromJobAndCompanyContext() {
        stubJob();
        when(ai.chat(anyList(), anyString()))
                .thenReturn("Company summary")   // company research call
                .thenReturn("## Questions\n- Explain Spring beans"); // prep call
        var research = new CompanyResearchEngine(ai, jobs, true);
        var svc = new InterviewPreparationService(ai, jobs, research, true);
        var out = svc.prepare(userId, jobId);
        assertTrue(out.isPresent());
        assertTrue(out.get().plan().contains("Questions"));
        assertEquals(jobId, out.get().jobId());
    }

    @Test
    void interviewPrepLlmFailureIsEmpty() {
        stubJob();
        var research = new CompanyResearchEngine(ai, jobs, false); // research off -> empty context
        var svc = new InterviewPreparationService(ai, jobs, research, true);
        when(ai.chat(anyList(), anyString())).thenThrow(new RuntimeException("boom"));
        assertTrue(svc.prepare(userId, jobId).isEmpty());
    }
}
