package ai.careerpilot.workflowplanner;

import ai.careerpilot.capability.CapabilityType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Phase 8 — the only {@link WorkflowStepTemplateProvider}: a static, deterministic step blueprint
 * per {@link WorkflowType}, matching the phase spec's own worked example for the Resume Workflow
 * (Analyze Resume → ATS Optimization → Generate Improvements → Approval) and mirrored in shape
 * for the other 14 types. Every template ends in a human-approval step, matching this codebase's
 * "human approval required before autonomous action" principle (Phase 2E's {@code
 * ApprovalService}, Phase 7's {@code AutoApplyEngine}) — a future executor is expected to honor
 * {@link WorkflowStep#approvalRequired()}, not this package.
 */
public class DefaultWorkflowStepTemplateProvider implements WorkflowStepTemplateProvider {

    private static final Duration STEP_DURATION = Duration.ofMinutes(3);
    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(10);

    private static final Map<WorkflowType, List<WorkflowStep>> TEMPLATES = Map.ofEntries(
            Map.entry(WorkflowType.RESUME, List.of(
                    step(1, "Analyze Resume", "Parse and extract structured data from the current resume.", CapabilityType.RESUME_ANALYSIS, false),
                    step(2, "ATS Optimization", "Score the resume against ATS parsing rules and target keywords.", CapabilityType.RESUME_ANALYSIS, false, 1),
                    step(3, "Generate Improvements", "Produce concrete, ranked improvement suggestions.", CapabilityType.RESUME_ANALYSIS, false, 2),
                    approval(4, 3))),
            Map.entry(WorkflowType.JOB_DISCOVERY, List.of(
                    step(1, "Fetch Candidate Pool", "Pull the current discovered-job pool for this candidate.", CapabilityType.JOB_RECOMMENDATION, false),
                    step(2, "Score and Rank", "Score and rank jobs against the candidate's profile.", CapabilityType.JOB_RECOMMENDATION, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.ATS, List.of(
                    step(1, "Parse Job Description", "Extract ATS-relevant keywords from the target job.", CapabilityType.RESUME_ANALYSIS, false),
                    step(2, "Score Resume Against ATS", "Score the resume against the parsed job's ATS profile.", CapabilityType.RESUME_ANALYSIS, false, 1),
                    step(3, "Recommend Keyword Changes", "Recommend keyword/section changes to raise the ATS score.", CapabilityType.RESUME_ANALYSIS, false, 2),
                    approval(4, 3))),
            Map.entry(WorkflowType.INTERVIEW, List.of(
                    step(1, "Generate Questions", "Generate likely interview questions for the target role.", CapabilityType.INTERVIEW_PREPARATION, false),
                    step(2, "Mock Interview", "Run a mock interview session against the generated questions.", CapabilityType.INTERVIEW_PREPARATION, false, 1),
                    step(3, "Evaluate Answers", "Evaluate answers and produce feedback.", CapabilityType.INTERVIEW_PREPARATION, false, 2),
                    approval(4, 3))),
            Map.entry(WorkflowType.LEARNING, List.of(
                    step(1, "Analyze Skill Gaps", "Compare current skills against target-role demand.", CapabilityType.LEARNING_HELP, false),
                    step(2, "Recommend Learning Path", "Recommend a ranked learning path to close the gaps.", CapabilityType.LEARNING_HELP, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.SALARY, List.of(
                    step(1, "Benchmark Compensation", "Benchmark target-role compensation against market data.", null, false),
                    step(2, "Draft Negotiation Strategy", "Draft a negotiation strategy from the benchmark.", null, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.COMPANY_INTELLIGENCE, List.of(
                    step(1, "Gather Company Signals", "Gather available public signals for the target company.", null, false),
                    step(2, "Build Knowledge Profile", "Summarize signals into a company knowledge profile.", null, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.OFFER_EVALUATION, List.of(
                    step(1, "Parse Offer", "Extract structured terms from the offer.", null, false),
                    step(2, "Compare Against Market", "Compare offer terms against market percentiles.", null, false, 1),
                    step(3, "Recommend Response", "Recommend accept/negotiate/decline with reasoning.", null, false, 2),
                    approval(4, 3))),
            Map.entry(WorkflowType.VISA, List.of(
                    step(1, "Assess Eligibility", "Assess visa/sponsorship eligibility for the target country.", null, false),
                    step(2, "Summarize Requirements", "Summarize documentation and timeline requirements.", null, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.RELOCATION, List.of(
                    step(1, "Compare Cost of Living", "Compare cost of living between current and target country.", null, false),
                    step(2, "Draft Relocation Timeline", "Draft a relocation timeline and checklist.", null, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.MISSION_PROGRESS, List.of(
                    step(1, "Read Strategy Plan", "Read the mission's latest strategy plan and actions.", CapabilityType.CAREER_STRATEGY, false),
                    step(2, "Compare Progress", "Compare current progress against the plan's expectations.", CapabilityType.CAREER_STRATEGY, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.CAREER_STRATEGY, List.of(
                    step(1, "Assess Trajectory", "Assess the candidate's current career trajectory.", CapabilityType.CAREER_STRATEGY, false),
                    step(2, "Recommend Next Steps", "Recommend the next strategic steps.", CapabilityType.CAREER_STRATEGY, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.LINKEDIN, List.of(
                    step(1, "Analyze Profile", "Analyze the current LinkedIn profile against the target role.", null, false),
                    step(2, "Recommend Edits", "Recommend headline/summary/experience edits.", null, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.NETWORKING, List.of(
                    step(1, "Identify Contacts", "Identify relevant networking contacts at target companies.", null, false),
                    step(2, "Draft Outreach", "Draft personalized outreach messages.", null, false, 1),
                    approval(3, 2))),
            Map.entry(WorkflowType.PORTFOLIO, List.of(
                    step(1, "Review Portfolio", "Review current portfolio/project work.", null, false),
                    step(2, "Recommend Additions", "Recommend additions or changes aligned to the target role.", null, false, 1),
                    approval(3, 2))));

    @Override
    public List<WorkflowStep> stepsFor(WorkflowType type) {
        return TEMPLATES.getOrDefault(type, List.of());
    }

    private static WorkflowStep step(int number, String name, String description, CapabilityType capability,
                                      boolean parallel, Integer... dependsOn) {
        return new WorkflowStep(number, name, description, capability, List.of(), List.of(), 1, STEP_TIMEOUT,
                false, List.of(dependsOn), parallel, STEP_DURATION, "node_" + number);
    }

    private static WorkflowStep approval(int number, int dependsOnStep) {
        return new WorkflowStep(number, "Approval", "Human approval gate before this workflow is considered complete.",
                null, List.of(), List.of(), 0, Duration.ofHours(24), true, List.of(dependsOnStep), false,
                Duration.ZERO, "node_" + number);
    }
}
