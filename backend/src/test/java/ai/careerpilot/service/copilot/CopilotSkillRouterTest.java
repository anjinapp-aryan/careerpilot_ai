package ai.careerpilot.service.copilot;

import ai.careerpilot.service.copilot.skill.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase 11B — routing regression test for {@link CopilotSkillRouter}. Existing keyword rules must
 * keep routing exactly as before; the new {@code DAILY_PRIORITY_BRIEFING} phrasing must route to
 * the new handler without stealing any existing skill's keywords (e.g. a bare "mission"/
 * "application" question must still land where it did before this phase).
 */
class CopilotSkillRouterTest {

    private final CopilotSkillRouter router = new CopilotSkillRouter(
            mock(ResumeAnalysisHandler.class), mock(AtsAnalysisHandler.class), mock(JobMatchHandler.class),
            mock(ApplicationStrategyHandler.class), mock(InterviewPrepHandler.class), mock(CareerGuidanceHandler.class),
            mock(WorkflowExplanationHandler.class), mock(SalaryGuidanceHandler.class), mock(SkillsGapHandler.class),
            mock(PersonalizedRecommendationsHandler.class), mock(ExplainLearningHandler.class),
            mock(ExplainApplicationDecisionHandler.class), mock(ExplainApplicationPackageHandler.class),
            mock(ExplainApplicationReviewHandler.class), mock(ExplainCompanyHandler.class),
            mock(CompareCompaniesHandler.class), mock(ShouldIApplyHandler.class), mock(CompanyRiskHandler.class),
            mock(CompanyTechnologyHandler.class), mock(CompanyInterviewHandler.class), mock(CompanyCultureHandler.class),
            mock(CompanyGrowthHandler.class), mock(JobDiscoveryHealthHandler.class),
            mock(StoryRecommendationHandler.class), mock(StoryGenerationHandler.class),
            mock(SubmissionStatusHandler.class), mock(ExplainApplicationStatusHandler.class),
            mock(OfferIntelligenceHandler.class), mock(DailyPriorityBriefingHandler.class),
            mock(GeneralAssistantHandler.class));

    @Test
    void explicitAction_routesToDailyPriorityBriefingHandler() {
        CopilotSkillHandler handler = router.route(CopilotSkill.DAILY_PRIORITY_BRIEFING.key(), null);
        assertThat(handler).isInstanceOf(DailyPriorityBriefingHandler.class);
    }

    @Test
    void whatShouldIDoToday_routesToDailyPriorityBriefing() {
        assertThat(router.route(null, "What should I do today?")).isInstanceOf(DailyPriorityBriefingHandler.class);
    }

    @Test
    void whatsBlockingMyMission_routesToDailyPriorityBriefing() {
        assertThat(router.route(null, "What is blocking my mission progress?"))
                .isInstanceOf(DailyPriorityBriefingHandler.class);
    }

    @Test
    void topPriority_routesToDailyPriorityBriefing() {
        assertThat(router.route(null, "What's my top priority right now?"))
                .isInstanceOf(DailyPriorityBriefingHandler.class);
    }

    @Test
    void dailyBriefing_routesToDailyPriorityBriefing() {
        assertThat(router.route(null, "Give me my daily briefing")).isInstanceOf(DailyPriorityBriefingHandler.class);
    }

    // --- Regression: existing routing must be unaffected by the new keyword rule ---

    @Test
    void bareMissionQuestion_stillRoutesToCareerGuidance() {
        assertThat(router.route(null, "I need career advice and guidance")).isInstanceOf(CareerGuidanceHandler.class);
    }

    @Test
    void applicationStrategyQuestion_stillRoutesToApplicationStrategy() {
        assertThat(router.route(null, "What should my application strategy be?"))
                .isInstanceOf(ApplicationStrategyHandler.class);
    }

    @Test
    void interviewQuestion_stillRoutesToInterviewPrep() {
        assertThat(router.route(null, "Help me prepare for my interview")).isInstanceOf(InterviewPrepHandler.class);
    }

    @Test
    void workflowQuestion_stillRoutesToWorkflowExplanation() {
        assertThat(router.route(null, "Explain my workflow results")).isInstanceOf(WorkflowExplanationHandler.class);
    }

    @Test
    void unmatchedMessage_fallsBackToGeneralAssistant() {
        assertThat(router.route(null, "hello there")).isInstanceOf(GeneralAssistantHandler.class);
    }
}
