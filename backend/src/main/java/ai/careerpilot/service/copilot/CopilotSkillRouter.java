package ai.careerpilot.service.copilot;

import ai.careerpilot.service.copilot.skill.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Routes Copilot requests to specialized skill handlers.
 * Manages the registry of 10 career intelligence skills and their handlers.
 */
@Service
public class CopilotSkillRouter {

    private static final Logger log = LoggerFactory.getLogger(CopilotSkillRouter.class);

    private final Map<CopilotSkill, CopilotSkillHandler> handlers;
    private final CopilotSkillHandler fallback;

    public CopilotSkillRouter(
            ResumeAnalysisHandler resumeAnalysis,
            AtsAnalysisHandler atsAnalysis,
            JobMatchHandler jobMatch,
            ApplicationStrategyHandler appStrategy,
            InterviewPrepHandler interviewPrep,
            CareerGuidanceHandler careerGuidance,
            WorkflowExplanationHandler workflowExplanation,
            SalaryGuidanceHandler salaryGuidance,
            SkillsGapHandler skillsGap,
            PersonalizedRecommendationsHandler recommendations,
            ExplainLearningHandler explainLearning,
            ExplainApplicationDecisionHandler explainApplicationDecision,
            ExplainApplicationPackageHandler explainApplicationPackage,
            ExplainApplicationReviewHandler explainApplicationReview,
            ExplainCompanyHandler explainCompany,
            CompareCompaniesHandler compareCompanies,
            ShouldIApplyHandler shouldIApply,
            CompanyRiskHandler companyRisk,
            CompanyTechnologyHandler companyTechnology,
            CompanyInterviewHandler companyInterview,
            CompanyCultureHandler companyCulture,
            CompanyGrowthHandler companyGrowth,
            JobDiscoveryHealthHandler jobDiscoveryHealth,
            StoryRecommendationHandler storyRecommendation,
            StoryGenerationHandler storyGeneration,
            SubmissionStatusHandler submissionStatus,
            ExplainApplicationStatusHandler explainApplicationStatus,
            GeneralAssistantHandler generalAssistant) {

        this.handlers = new EnumMap<>(CopilotSkill.class);
        this.handlers.put(CopilotSkill.RESUME_ANALYSIS, resumeAnalysis);
        this.handlers.put(CopilotSkill.ATS_ANALYSIS, atsAnalysis);
        this.handlers.put(CopilotSkill.JOB_MATCH_ANALYSIS, jobMatch);
        this.handlers.put(CopilotSkill.APPLICATION_STRATEGY, appStrategy);
        this.handlers.put(CopilotSkill.INTERVIEW_PREPARATION, interviewPrep);
        this.handlers.put(CopilotSkill.CAREER_GUIDANCE, careerGuidance);
        this.handlers.put(CopilotSkill.WORKFLOW_EXPLANATION, workflowExplanation);
        this.handlers.put(CopilotSkill.SALARY_GUIDANCE, salaryGuidance);
        this.handlers.put(CopilotSkill.SKILLS_GAP_ANALYSIS, skillsGap);
        this.handlers.put(CopilotSkill.PERSONALIZED_RECOMMENDATIONS, recommendations);
        this.handlers.put(CopilotSkill.EXPLAIN_LEARNING, explainLearning);
        this.handlers.put(CopilotSkill.EXPLAIN_APPLICATION_DECISION, explainApplicationDecision);
        this.handlers.put(CopilotSkill.EXPLAIN_APPLICATION_PACKAGE, explainApplicationPackage);
        this.handlers.put(CopilotSkill.EXPLAIN_APPLICATION_REVIEW, explainApplicationReview);
        this.handlers.put(CopilotSkill.EXPLAIN_COMPANY, explainCompany);
        this.handlers.put(CopilotSkill.COMPARE_COMPANIES, compareCompanies);
        this.handlers.put(CopilotSkill.SHOULD_I_APPLY, shouldIApply);
        this.handlers.put(CopilotSkill.COMPANY_RISK, companyRisk);
        this.handlers.put(CopilotSkill.COMPANY_TECHNOLOGY, companyTechnology);
        this.handlers.put(CopilotSkill.COMPANY_INTERVIEW, companyInterview);
        this.handlers.put(CopilotSkill.COMPANY_CULTURE, companyCulture);
        this.handlers.put(CopilotSkill.COMPANY_GROWTH, companyGrowth);
        this.handlers.put(CopilotSkill.JOB_DISCOVERY_HEALTH, jobDiscoveryHealth);
        this.handlers.put(CopilotSkill.SUGGEST_STAR_STORY, storyRecommendation);
        this.handlers.put(CopilotSkill.GENERATE_STAR_STORY, storyGeneration);
        this.handlers.put(CopilotSkill.SUBMISSION_STATUS, submissionStatus);
        this.handlers.put(CopilotSkill.EXPLAIN_SUBMISSION_STRATEGY, submissionStatus);
        this.handlers.put(CopilotSkill.EXPLAIN_APPLICATION_STATUS, explainApplicationStatus);

        this.fallback = generalAssistant;

        log.info("CopilotSkillRouter initialized with {} skill handlers", handlers.size());
    }

    /**
     * Routes a Copilot request to the appropriate skill handler.
     * If an action is specified, use it; otherwise infer from message intent.
     */
    public CopilotSkillHandler route(String action, String message) {
        if (action != null && !action.isBlank()) {
            CopilotSkill skill = CopilotSkill.fromAction(action);
            if (skill != null && handlers.containsKey(skill)) {
                log.debug("Routing Copilot request to explicit action: {}", action);
                return handlers.get(skill);
            }
        }

        if (message != null && !message.isBlank()) {
            CopilotSkill skill = inferSkillFromMessage(message);
            if (skill != null && handlers.containsKey(skill)) {
                log.debug("Routed Copilot request to inferred skill: {}", skill.key());
                return handlers.get(skill);
            }
        }

        log.debug("Using general assistant fallback for Copilot request");
        return fallback;
    }

    /**
     * Infers the skill from the user's free-text message.
     * Simple keyword matching; can be upgraded to NLP intent detection.
     */
    private CopilotSkill inferSkillFromMessage(String message) {
        String lower = message.toLowerCase();

        // Applications Page command-center intents — checked first (specific phrasing) so they win
        // over the more generic INTERVIEW_PREPARATION/APPLICATION_STRATEGY branches below.
        if ((lower.contains("stuck") || lower.contains("why is this application") || lower.contains("why hasn't")
                || lower.contains("improve my chances") || lower.contains("chances of getting")
                || lower.contains("should i follow up") || lower.contains("follow-up email") || lower.contains("follow up email")
                || lower.contains("outreach email") || lower.contains("predict") && lower.contains("interview")
                || lower.contains("this application"))) {
            return CopilotSkill.EXPLAIN_APPLICATION_STATUS;
        }

        if (lower.contains("resume") && (lower.contains("improve") || lower.contains("analyze") || lower.contains("review"))) {
            return CopilotSkill.RESUME_ANALYSIS;
        }
        if (lower.contains("ats") || lower.contains("applicant tracking")) {
            return CopilotSkill.ATS_ANALYSIS;
        }
        if ((lower.contains("match") || lower.contains("fit")) && (lower.contains("job") || lower.contains("role"))) {
            return CopilotSkill.JOB_MATCH_ANALYSIS;
        }
        if (lower.contains("interview")) {
            return CopilotSkill.INTERVIEW_PREPARATION;
        }
        if (lower.contains("workflow") || lower.contains("results") || lower.contains("explain")) {
            return CopilotSkill.WORKFLOW_EXPLANATION;
        }
        if (lower.contains("salary") || lower.contains("compensation") || lower.contains("pay")) {
            return CopilotSkill.SALARY_GUIDANCE;
        }
        if (lower.contains("skill") && lower.contains("gap")) {
            return CopilotSkill.SKILLS_GAP_ANALYSIS;
        }
        if (lower.contains("application") && (lower.contains("strategy") || lower.contains("next"))) {
            return CopilotSkill.APPLICATION_STRATEGY;
        }
        if (lower.contains("career") && (lower.contains("guidance") || lower.contains("advice"))) {
            return CopilotSkill.CAREER_GUIDANCE;
        }
        if ((lower.contains("auto") && lower.contains("apply")) || lower.contains("application decision")
                || (lower.contains("why") && lower.contains("human review")) || lower.contains("autopilot")) {
            return CopilotSkill.EXPLAIN_APPLICATION_DECISION;
        }
        if ((lower.contains("review") && (lower.contains("ai") || lower.contains("quality") || lower.contains("verdict")
                || lower.contains("reviewer") || lower.contains("final"))) || lower.contains("application review")) {
            return CopilotSkill.EXPLAIN_APPLICATION_REVIEW;
        }
        if (lower.contains("package") && (lower.contains("application") || lower.contains("validation")
                || lower.contains("ready") || lower.contains("quality") || lower.contains("explain"))) {
            return CopilotSkill.EXPLAIN_APPLICATION_PACKAGE;
        }
        if (lower.contains("learning") || lower.contains("learned") || lower.contains("success pattern")
                || lower.contains("failure pattern") || (lower.contains("boost") && lower.contains("why"))) {
            return CopilotSkill.EXPLAIN_LEARNING;
        }
        if (lower.contains("recommend")) {
            return CopilotSkill.PERSONALIZED_RECOMMENDATIONS;
        }
        if ((lower.contains("provider") || lower.contains("source") || lower.contains("ingestion"))
                && (lower.contains("job") || lower.contains("discovery") || lower.contains("health")
                    || lower.contains("down") || lower.contains("failing"))) {
            return CopilotSkill.JOB_DISCOVERY_HEALTH;
        }
        // Phase 7.13 — company intelligence intents. Checked after the specific skills above so
        // e.g. "interview" alone still routes to INTERVIEW_PREPARATION as before (additive routing).
        if (lower.contains("compare") && (lower.contains("company") || lower.contains("companies"))) {
            return CopilotSkill.COMPARE_COMPANIES;
        }
        if (lower.contains("should i apply")) {
            return CopilotSkill.SHOULD_I_APPLY;
        }
        if (lower.contains("company") && (lower.contains("risk") || lower.contains("red flag"))) {
            return CopilotSkill.COMPANY_RISK;
        }
        if (lower.contains("company") && (lower.contains("tech stack") || lower.contains("technolog"))) {
            return CopilotSkill.COMPANY_TECHNOLOGY;
        }
        if (lower.contains("company") && lower.contains("interview")) {
            return CopilotSkill.COMPANY_INTERVIEW;
        }
        if (lower.contains("culture") || lower.contains("work-life") || lower.contains("work life")) {
            return CopilotSkill.COMPANY_CULTURE;
        }
        if (lower.contains("company") && (lower.contains("growth") || lower.contains("long-term")
                || lower.contains("long term"))) {
            return CopilotSkill.COMPANY_GROWTH;
        }
        if ((lower.contains("about") || lower.contains("explain") || lower.contains("tell me")
                || lower.contains("know")) && lower.contains("company")) {
            return CopilotSkill.EXPLAIN_COMPANY;
        }
        // Phase 7.15 — STAR story intents, checked after the more specific skills above.
        if (lower.contains("star") && (lower.contains("story") || lower.contains("stories"))
                && (lower.contains("best") || lower.contains("suggest") || lower.contains("which")
                    || lower.contains("missing"))) {
            return CopilotSkill.SUGGEST_STAR_STORY;
        }
        if ((lower.contains("star") && (lower.contains("story") || lower.contains("stories")))
                || (lower.contains("behavioral") && lower.contains("story"))
                || (lower.contains("bullet") && lower.contains("star"))) {
            return CopilotSkill.GENERATE_STAR_STORY;
        }

        // Phase 7.16 — application submission pipeline intents, checked after the more specific
        // skills above so e.g. "application strategy" alone still routes to APPLICATION_STRATEGY.
        if (lower.contains("submission status") || (lower.contains("submission") && lower.contains("status"))) {
            return CopilotSkill.SUBMISSION_STATUS;
        }
        if (lower.contains("why human approval") || (lower.contains("why") && lower.contains("submission") && lower.contains("fail"))) {
            return CopilotSkill.SUBMISSION_STATUS;
        }
        if (lower.contains("submission strategy")) {
            return CopilotSkill.EXPLAIN_SUBMISSION_STRATEGY;
        }

        return null;
    }

    public CopilotSkillHandler getHandler(CopilotSkill skill) {
        return handlers.getOrDefault(skill, fallback);
    }

    public Collection<CopilotSkillHandler> allHandlers() {
        return handlers.values();
    }
}
