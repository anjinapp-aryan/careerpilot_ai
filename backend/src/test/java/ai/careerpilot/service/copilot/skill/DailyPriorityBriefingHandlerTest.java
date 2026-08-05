package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.CareerContextService;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyPriorityBriefingHandlerTest {

    private final CareerContextRetriever retriever = mock(CareerContextRetriever.class);
    private final CareerContextService careerContextService = mock(CareerContextService.class);
    private final DailyPriorityBriefingHandler handler =
            new DailyPriorityBriefingHandler(retriever, careerContextService);

    private AuthenticatedUser user() {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "u@example.com", "USER");
    }

    private SkillContext skillContext() {
        return new SkillContext(user(), "what should i do today", null, "dashboard");
    }

    @Test
    void skill_isDailyPriorityBriefing() {
        assertThat(handler.skill()).isEqualTo(CopilotSkill.DAILY_PRIORITY_BRIEFING);
    }

    @Test
    void assembleContext_populatesCareerContextEvenWhenGlobalFlagWouldBeOff() throws Exception {
        CareerContextService.CareerContext ctx = new CareerContextService.CareerContext(
                null, List.of(), null, null, null, List.of(), "No verified historical trend available.", List.of());
        SkillContext skillCtx = skillContext();
        when(careerContextService.getCareerContext(skillCtx.user())).thenReturn(ctx);

        handler.assembleContext(skillCtx);

        assertThat(skillCtx.careerContext()).isSameAs(ctx);
    }

    @Test
    void assembleContext_neverThrowsWhenCareerContextServiceFails() {
        SkillContext skillCtx = skillContext();
        when(careerContextService.getCareerContext(skillCtx.user())).thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> handler.assembleContext(skillCtx));
        assertThat(skillCtx.careerContext()).isNull();
    }

    @Test
    void contextBlock_pointsToTheSharedCareerContextSection() {
        String block = handler.contextBlock(skillContext());
        assertThat(block).contains("Daily Priority Briefing");
    }

    @Test
    void systemPrompt_forbidsFabricationAndDefinesNoDataFallback() {
        String prompt = handler.systemPrompt(skillContext());
        assertThat(prompt).contains("I don't currently have verified information to build a priority briefing.");
    }
}
