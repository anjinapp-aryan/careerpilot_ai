package ai.careerpilot.service;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Data retrieval layer for RAG-style context assembly.
 * Fetches user data from Neon PostgreSQL and builds structured context
 * for the AI Gateway to process. This is the "memory" system of the
 * Career Intelligence Assistant.
 */
@Service
public class CareerContextRetriever {

    private static final Logger log = LoggerFactory.getLogger(CareerContextRetriever.class);

    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final WorkflowRunRepository workflowRuns;

    public CareerContextRetriever(ResumeRepository resumes, JobRepository jobs,
                                  ApplicationRepository applications, WorkflowRunRepository workflowRuns) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.applications = applications;
        this.workflowRuns = workflowRuns;
    }

    @Transactional(readOnly = true)
    public ResumeContext getResumeContext(AuthenticatedUser user, String resumeId) {
        Resume r = resolveResume(user, resumeId)
                .orElseThrow(() -> new IllegalArgumentException("Resume not found or access denied"));
        return new ResumeContext(
                r.getId().toString(),
                r.getFilename(),
                r.getParsedText(),
                r.getExtractedSkillsJson(),
                r.getResumeScore(),
                null,
                null
        );
    }

    @Transactional(readOnly = true)
    public JobContext getJobContext(AuthenticatedUser user, String jobId) {
        Job j = resolveJob(user, jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found or access denied"));
        return new JobContext(
                j.getId().toString(),
                j.getTitle(),
                j.getCompany(),
                j.getLocation(),
                j.getSalaryRange(),
                j.getDescription(),
                null,
                null
        );
    }

    @Transactional(readOnly = true)
    public ApplicationContext getApplicationContext(AuthenticatedUser user, String applicationId) {
        Application a = resolveApplication(user, applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found or access denied"));
        Job job = jobs.findById(a.getJobId()).orElse(null);
        Resume resume = resumes.findByUserIdOrderByCreatedAtDesc(user.userId()).stream().findFirst().orElse(null);

        return new ApplicationContext(
                a.getId().toString(),
                a.getStatus(),
                a.getMatchScore(),
                a.getAtsScore(),
                a.getCreatedAt(),
                a.getNextAction(),
                a.getNotes(),
                job != null ? new JobContext(
                        job.getId().toString(), job.getTitle(), job.getCompany(),
                        job.getLocation(), job.getSalaryRange(), job.getDescription(),
                        null, null
                ) : null,
                resume != null ? new ResumeContext(
                        resume.getId().toString(), resume.getFilename(), resume.getParsedText(),
                        resume.getExtractedSkillsJson(), resume.getResumeScore(),
                        null, null
                ) : null
        );
    }

    @Transactional(readOnly = true)
    public WorkflowContext getWorkflowContext(AuthenticatedUser user, String workflowId) {
        WorkflowRun run = resolveWorkflowRun(user, workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found or access denied"));
        return new WorkflowContext(
                run.getThreadId(),
                run.getStatus(),
                run.getTargetRole(),
                run.getTargetSeniority(),
                run.getResumeScore(),
                run.getAtsScore(),
                run.getJobMatchScore(),
                run.getInterviewReadinessScore(),
                run.getState(),
                run.getErrorMessage()
        );
    }

    @Transactional(readOnly = true)
    public UserProfileContext getUserProfileContext(AuthenticatedUser user) {
        List<Resume> userResumes = resumes.findByUserIdOrderByCreatedAtDesc(user.userId());
        List<Application> userApps = applications.findByUserIdOrderByCreatedAtDesc(user.userId());
        List<WorkflowRun> userRuns = workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(user.userId());

        return new UserProfileContext(
                user.userId().toString(),
                userResumes.size(),
                userApps.size(),
                userRuns.size(),
                userResumes.stream()
                        .map(r -> new ResumeContext(r.getId().toString(), r.getFilename(), r.getParsedText(),
                                r.getExtractedSkillsJson(), r.getResumeScore(), null, null))
                        .collect(Collectors.toList()),
                userApps.stream()
                        .map(a -> new ApplicationContext(a.getId().toString(), a.getStatus(), a.getMatchScore(),
                                a.getAtsScore(), a.getCreatedAt(), a.getNextAction(), a.getNotes(), null, null))
                        .collect(Collectors.toList())
        );
    }

    private Optional<Resume> resolveResume(AuthenticatedUser user, String id) {
        Optional<Resume> chosen = tryParseUuid(id)
                .flatMap(resumes::findById)
                .filter(r -> r.getUserId().equals(user.userId()));
        if (chosen.isPresent()) return chosen;
        return resumes.findByUserIdOrderByCreatedAtDesc(user.userId()).stream().findFirst();
    }

    private Optional<Job> resolveJob(AuthenticatedUser user, String id) {
        Optional<Job> chosen = tryParseUuid(id).flatMap(jobs::findById);
        if (chosen.isPresent() && (chosen.get().getOrgId() == null || chosen.get().getOrgId().equals(user.orgId()))) {
            return chosen;
        }
        return Optional.empty();
    }

    private Optional<Application> resolveApplication(AuthenticatedUser user, String id) {
        Optional<Application> chosen = tryParseUuid(id)
                .flatMap(applications::findById)
                .filter(a -> a.getUserId().equals(user.userId()));
        if (chosen.isPresent()) return chosen;
        return applications.findByUserIdOrderByCreatedAtDesc(user.userId()).stream().findFirst();
    }

    private Optional<WorkflowRun> resolveWorkflowRun(AuthenticatedUser user, String threadId) {
        if (threadId != null && !threadId.isBlank()) {
            Optional<WorkflowRun> chosen = workflowRuns.findByThreadId(threadId)
                    .filter(r -> r.getUserId().equals(user.userId()));
            if (chosen.isPresent()) return chosen;
        }
        List<WorkflowRun> recent = workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(user.userId());
        return recent.stream().findFirst();
    }

    private Optional<UUID> tryParseUuid(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(s.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ---- Context records (used by skill handlers) ----

    public record ResumeContext(
            String id,
            String filename,
            String parsedText,
            String skillsJson,
            Integer resumeScore,
            Integer atsScore,
            Integer matchScore) {}

    public record JobContext(
            String id,
            String title,
            String company,
            String location,
            String salaryRange,
            String description,
            String requiredSkillsJson,
            String atsCompatibility) {}

    public record ApplicationContext(
            String id,
            String status,
            Integer matchScore,
            Integer atsScore,
            java.time.Instant createdAt,
            String nextAction,
            String notes,
            JobContext job,
            ResumeContext resume) {}

    public record WorkflowContext(
            String threadId,
            String status,
            String targetRole,
            String targetSeniority,
            Integer resumeScore,
            Integer atsScore,
            Integer jobMatchScore,
            Integer interviewReadinessScore,
            String state,
            String errorMessage) {}

    public record UserProfileContext(
            String userId,
            int resumeCount,
            int applicationCount,
            int workflowCount,
            List<ResumeContext> resumes,
            List<ApplicationContext> applications) {}
}
