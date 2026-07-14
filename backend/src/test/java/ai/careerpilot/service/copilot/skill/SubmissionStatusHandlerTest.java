package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.submission.api.SubmissionCopilotContextService;
import ai.careerpilot.submission.api.SubmissionCopilotContextService.SubmissionCopilotContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmissionStatusHandlerTest {

    private final CareerContextRetriever retriever = mock(CareerContextRetriever.class);
    private final SubmissionCopilotContextService submissionContext = mock(SubmissionCopilotContextService.class);
    private final SubmissionStatusHandler handler = new SubmissionStatusHandler(retriever, submissionContext);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "USER");

    @Test
    void skillIsSubmissionStatus() {
        assertEquals(CopilotSkill.SUBMISSION_STATUS, handler.skill());
    }

    @Test
    void assembleContextPopulatesSubmissionField() throws Exception {
        when(submissionContext.forUser(user.userId())).thenReturn(new SubmissionCopilotContext(true, List.of()));
        SkillContext ctx = new SkillContext(user, "what's the status of my application submission", null, "jobs");
        handler.assembleContext(ctx);
        assertNotNull(ctx.submission());
        assertTrue(ctx.submission().enabled());
    }

    @Test
    void assembleContextSourceIsAddedOnlyWhenEnabled() throws Exception {
        when(submissionContext.forUser(user.userId())).thenReturn(new SubmissionCopilotContext(true, List.of()));
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        handler.assembleContext(ctx);
        assertTrue(ctx.sources().stream().anyMatch(s -> s.contains("Application Submission Pipeline")));
    }

    @Test
    void assembleContextSourceNotAddedWhenDisabled() throws Exception {
        when(submissionContext.forUser(user.userId())).thenReturn(new SubmissionCopilotContext(false, List.of()));
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        handler.assembleContext(ctx);
        assertTrue(ctx.sources().stream().noneMatch(s -> s.contains("Application Submission Pipeline")));
    }

    @Test
    void contextBlockReportsDisabledWhenFeatureOff() {
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        ctx.submission(new SubmissionCopilotContext(false, List.of()));
        assertTrue(handler.contextBlock(ctx).toLowerCase().contains("not enabled"));
    }

    @Test
    void contextBlockReportsNullSubmissionAsDisabled() {
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        assertTrue(handler.contextBlock(ctx).toLowerCase().contains("not enabled"));
    }

    @Test
    void contextBlockReportsNoSessionsWhenEmpty() {
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        ctx.submission(new SubmissionCopilotContext(true, List.of()));
        assertTrue(handler.contextBlock(ctx).toLowerCase().contains("no application submission sessions"));
    }

    @Test
    void contextBlockRendersSessionDetails() {
        UUID jobId = UUID.randomUUID();
        ApplicationSubmissionSession s = ApplicationSubmissionSession.builder()
                .id(UUID.randomUUID()).userId(user.userId()).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL)
                .submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL)
                .provider("greenhouse").build();
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        ctx.submission(new SubmissionCopilotContext(true, List.of(s)));
        String block = handler.contextBlock(ctx);
        assertTrue(block.contains("WAITING_APPROVAL"));
        assertTrue(block.contains("greenhouse"));
        assertTrue(block.contains(jobId.toString()));
    }

    @Test
    void contextBlockRendersFailureReasonVerbatim() {
        ApplicationSubmissionSession s = ApplicationSubmissionSession.builder()
                .id(UUID.randomUUID()).userId(user.userId()).jobId(UUID.randomUUID())
                .status(ApplicationSubmissionSession.STATUS_FAILED)
                .submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL)
                .failureReason("job validation failed: job not found").build();
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        ctx.submission(new SubmissionCopilotContext(true, List.of(s)));
        assertTrue(handler.contextBlock(ctx).contains("job validation failed: job not found"));
    }

    @Test
    void systemPromptIsNonEmptyAndGroundedAgainstFabrication() {
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        String prompt = handler.systemPrompt(ctx);
        assertFalse(prompt.isBlank());
        assertTrue(prompt.contains("Never invent"));
    }
}
