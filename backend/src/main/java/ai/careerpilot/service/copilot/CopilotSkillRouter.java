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
        if (lower.contains("recommend")) {
            return CopilotSkill.PERSONALIZED_RECOMMENDATIONS;
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
