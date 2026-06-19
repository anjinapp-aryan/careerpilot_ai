package ai.careerpilot.service;

import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Copilot's intelligence router — the layer that makes the assistant
 * context-aware. Given the page the user is on, the chosen action, and an
 * optional entity id, it (1) assembles a grounded context block from existing
 * domain services/repositories and (2) produces the specialised system prompt
 * for that task. All data access is ownership-checked, mirroring the manual
 * multi-tenant isolation used across the codebase.
 *
 * This is deliberately the single seam where "what can the Copilot do on each
 * page" is defined, so new capabilities are added here, not scattered.
 */
@Service
public class AgentOrchestrator {

    private static final int MAX_RESUME_CHARS = 6000;
    private static final int MAX_JOB_CHARS = 4000;
    private static final int MAX_STATE_CHARS = 4000;

    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final WorkflowRunRepository workflowRuns;

    public AgentOrchestrator(ResumeRepository resumes, JobRepository jobs,
                             ApplicationRepository applications, WorkflowRunRepository workflowRuns) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.applications = applications;
        this.workflowRuns = workflowRuns;
    }

    // ----------------------------------------------------------------------
    // System prompt
    // ----------------------------------------------------------------------

    private static final String BASE_PERSONA = """
            You are CareerPilot Copilot, the primary AI intelligence layer of CareerPilot AI —
            an enterprise career-management platform. You are an expert career coach, technical
            recruiter, and resume/ATS specialist.

            Style: concise, specific, and actionable. Prefer short paragraphs and tight bullet
            lists. Use markdown. Never invent facts about the user — rely only on the CONTEXT
            provided. If the context is missing something you need, say so and ask one focused
            follow-up question. Do not fabricate scores, companies, or experience.
            """;

    /** Task-specific guidance appended to the persona for the active (page, action). */
    public String systemPrompt(String page, String action) {
        String task = switch (action == null ? "" : action) {
            case "improve_resume" -> """
                    TASK — Improve Resume: Review the candidate's resume in CONTEXT and give a
                    prioritised list of concrete edits. Focus on impact: strong action verbs,
                    quantified achievements, role-aligned keywords, and removing filler. Where
                    helpful, show a "before → after" rewrite of weak bullets.""";
            case "ats_analysis" -> """
                    TASK — ATS Analysis: Evaluate how well the resume in CONTEXT will pass
                    Applicant Tracking Systems. Call out missing keywords/skills, formatting risks,
                    section structure, and parseability. End with an estimated ATS readiness band
                    (Low / Medium / High) and the top 3 fixes.""";
            case "job_matching" -> """
                    TASK — Job Matching: Compare the candidate's resume against the job(s) in
                    CONTEXT. Explain the match across skills, seniority, and domain. Give a
                    qualitative match assessment, the strongest alignment points, and the gaps the
                    candidate should address before applying.""";
            case "job_explanation" -> """
                    TASK — Explain this Job: Break down the job in CONTEXT in plain language: what
                    the role really does day-to-day, the must-have vs nice-to-have requirements,
                    seniority signals, likely interview focus, and any red/green flags.""";
            case "followup" -> """
                    TASK — Follow-up Recommendations: Based on the application in CONTEXT (its
                    status, timeline, and notes), recommend the next best action. Suggest timing,
                    who to contact, and provide a short ready-to-send follow-up message tailored to
                    the current stage.""";
            case "interview_prediction" -> """
                    TASK — Interview Prediction: Using the application, job, and resume in CONTEXT,
                    assess the likelihood of advancing to / succeeding in interviews. Identify the
                    questions most likely to be asked, the candidate's strengths to lean on, and the
                    weak spots to prepare for. Be honest about probability.""";
            case "explain_results" -> """
                    TASK — Explain Results: The CONTEXT contains a completed AI workflow run with
                    scores and agent state. Explain what the results mean for this candidate, what
                    drove each score, and the highest-leverage next steps to improve them.""";
            case "explain_failures" -> """
                    TASK — Explain Failures: The CONTEXT contains a workflow run that failed or was
                    interrupted. Explain in plain terms what likely went wrong, whether it is a user
                    input issue or a system issue, and exactly how to recover or re-run successfully.""";
            default -> """
                    TASK — Career Assistant: Help the user with whatever they ask, grounded in the
                    CONTEXT for the page they are on.""";
        };
        String where = page == null || page.isBlank() ? "" :
                "\nThe user is currently on the \"" + page + "\" page of the app.";
        return BASE_PERSONA + where + "\n\n" + task;
    }

    /** The message used when a quick-action button is clicked without typed text. */
    public String defaultMessage(String action) {
        return switch (action == null ? "" : action) {
            case "improve_resume" -> "Improve my resume and show me the highest-impact edits.";
            case "ats_analysis" -> "Run an ATS analysis on my resume and tell me what to fix.";
            case "job_matching" -> "How well do I match this job, and what gaps should I close?";
            case "job_explanation" -> "Explain this job and what it really involves.";
            case "followup" -> "What's my best next follow-up for this application?";
            case "interview_prediction" -> "Predict how I'll do in interviews for this and how to prepare.";
            case "explain_results" -> "Explain the results of this workflow run.";
            case "explain_failures" -> "Why did this workflow run fail, and how do I fix it?";
            default -> "Help me with my career.";
        };
    }

    // ----------------------------------------------------------------------
    // Context assembly (grounding)
    // ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public String contextBlock(AuthenticatedUser user, String page, String action, String contextId) {
        String p = page == null ? "" : page;
        return switch (p) {
            case "resume" -> resumeContext(user, contextId);
            case "jobs" -> jobContext(user, contextId);
            case "applications" -> applicationContext(user, contextId);
            case "workflow" -> workflowContext(user, contextId);
            default -> overviewContext(user);
        };
    }

    private String resumeContext(AuthenticatedUser user, String contextId) {
        Resume r = resolveResume(user, contextId).orElse(null);
        if (r == null) {
            return "CONTEXT: The user has not uploaded a resume yet. Encourage them to upload one "
                    + "on the Resumes page to unlock resume and ATS analysis.";
        }
        return "CONTEXT — Resume\n"
                + "Filename: " + nullSafe(r.getFilename()) + "\n"
                + "Stored ATS/resume score: " + (r.getResumeScore() == null ? "not scored yet" : r.getResumeScore()) + "\n"
                + "Extracted skills: " + truncate(nullSafe(r.getExtractedSkillsJson()), 1200) + "\n"
                + "----- RESUME TEXT -----\n"
                + truncate(nullSafe(r.getParsedText()), MAX_RESUME_CHARS);
    }

    private String jobContext(AuthenticatedUser user, String contextId) {
        Job job = resolveJob(user, contextId).orElse(null);
        Resume resume = resumes.findByUserIdOrderByCreatedAtDesc(user.userId()).stream().findFirst().orElse(null);
        StringBuilder sb = new StringBuilder();
        if (job == null) {
            sb.append("CONTEXT: No specific job is selected. Ask the user which role they want help with.\n");
        } else {
            sb.append("CONTEXT — Job\n")
              .append("Title: ").append(nullSafe(job.getTitle())).append("\n")
              .append("Company: ").append(nullSafe(job.getCompany())).append("\n")
              .append("Location: ").append(nullSafe(job.getLocation())).append("\n")
              .append("Salary: ").append(nullSafe(job.getSalaryRange())).append("\n")
              .append("----- JOB DESCRIPTION -----\n")
              .append(truncate(nullSafe(job.getDescription()), MAX_JOB_CHARS)).append("\n\n");
        }
        if (resume != null) {
            sb.append("CONTEXT — Candidate resume (for matching)\n")
              .append(truncate(nullSafe(resume.getParsedText()), MAX_RESUME_CHARS));
        } else {
            sb.append("The candidate has no resume on file yet.");
        }
        return sb.toString();
    }

    private String applicationContext(AuthenticatedUser user, String contextId) {
        Application app = resolveApplication(user, contextId).orElse(null);
        if (app == null) {
            return "CONTEXT: The user has no applications yet. Suggest saving or applying to jobs "
                    + "from the Jobs page.";
        }
        Job job = jobs.findById(app.getJobId()).orElse(null);
        String when = app.getCreatedAt() == null ? "unknown" : app.getCreatedAt().toString();
        StringBuilder sb = new StringBuilder("CONTEXT — Application\n");
        sb.append("Status: ").append(nullSafe(app.getStatus())).append("\n")
          .append("Match score: ").append(orNa(app.getMatchScore())).append("\n")
          .append("ATS score: ").append(orNa(app.getAtsScore())).append("\n")
          .append("Created: ").append(when).append("\n")
          .append("Next action: ").append(nullSafe(app.getNextAction())).append("\n")
          .append("Notes: ").append(truncate(nullSafe(app.getNotes()), 1500)).append("\n");
        if (job != null) {
            sb.append("----- TARGET JOB -----\n")
              .append("Title: ").append(nullSafe(job.getTitle())).append(" @ ").append(nullSafe(job.getCompany())).append("\n")
              .append(truncate(nullSafe(job.getDescription()), MAX_JOB_CHARS));
        }
        return sb.toString();
    }

    private String workflowContext(AuthenticatedUser user, String contextId) {
        WorkflowRun run = resolveWorkflowRun(user, contextId).orElse(null);
        if (run == null) {
            return "CONTEXT: No workflow run is available. Suggest starting a run from the AI "
                    + "Workflow page.";
        }
        return "CONTEXT — AI Workflow Run\n"
                + "Thread: " + nullSafe(run.getThreadId()) + "\n"
                + "Status: " + nullSafe(run.getStatus()) + "\n"
                + "Target role: " + nullSafe(run.getTargetRole()) + " (" + nullSafe(run.getTargetSeniority()) + ")\n"
                + "Scores — resume: " + orNa(run.getResumeScore())
                + ", ATS: " + orNa(run.getAtsScore())
                + ", job match: " + orNa(run.getJobMatchScore())
                + ", interview readiness: " + orNa(run.getInterviewReadinessScore()) + "\n"
                + "Error: " + nullSafe(run.getErrorMessage()) + "\n"
                + "----- AGENT STATE (JSON) -----\n"
                + truncate(nullSafe(run.getState()), MAX_STATE_CHARS);
    }

    private String overviewContext(AuthenticatedUser user) {
        long resumeCount = resumes.findByUserIdOrderByCreatedAtDesc(user.userId()).size();
        long appCount = applications.findByUserIdOrderByCreatedAtDesc(user.userId()).size();
        long runCount = workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(user.userId()).size();
        return "CONTEXT — Account overview\n"
                + "Resumes on file: " + resumeCount + "\n"
                + "Applications tracked: " + appCount + "\n"
                + "Recent AI workflow runs: " + runCount;
    }

    // ----------------------------------------------------------------------
    // Ownership-checked resolution helpers
    // ----------------------------------------------------------------------

    private Optional<Resume> resolveResume(AuthenticatedUser user, String id) {
        Optional<Resume> chosen = parseUuid(id)
                .flatMap(resumes::findById)
                .filter(r -> r.getUserId().equals(user.userId()));
        if (chosen.isPresent()) return chosen;
        return resumes.findByUserIdOrderByCreatedAtDesc(user.userId()).stream().findFirst();
    }

    private Optional<Job> resolveJob(AuthenticatedUser user, String id) {
        return parseUuid(id)
                .flatMap(jobs::findById)
                .filter(j -> j.getOrgId() == null || j.getOrgId().equals(user.orgId()));
    }

    private Optional<Application> resolveApplication(AuthenticatedUser user, String id) {
        Optional<Application> chosen = parseUuid(id)
                .flatMap(applications::findById)
                .filter(a -> a.getUserId().equals(user.userId()));
        if (chosen.isPresent()) return chosen;
        return applications.findByUserIdOrderByCreatedAtDesc(user.userId()).stream().findFirst();
    }

    private Optional<WorkflowRun> resolveWorkflowRun(AuthenticatedUser user, String threadId) {
        Optional<WorkflowRun> chosen = (threadId == null || threadId.isBlank())
                ? Optional.empty()
                : workflowRuns.findByThreadId(threadId).filter(r -> r.getUserId().equals(user.userId()));
        if (chosen.isPresent()) return chosen;
        List<WorkflowRun> recent = workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(user.userId());
        return recent.stream().findFirst();
    }

    private static Optional<UUID> parseUuid(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(s.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "n/a" : s;
    }

    private static String orNa(Integer v) {
        return v == null ? "n/a" : v.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…[truncated]";
    }
}
