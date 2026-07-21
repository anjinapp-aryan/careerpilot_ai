package ai.careerpilot.service;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.security.AuthenticatedUser;
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

    private final ResumeRepository resumes;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final WorkflowRunRepository workflowRuns;
    private final WorkflowService workflowService;
    private final DailyDiscoveryAnalyticsRepository dailyDiscoveryAnalytics;
    private final DailyCareerSummaryRepository dailyCareerSummaries;
    private final ai.careerpilot.repo.CandidateProfileRepository candidateProfiles;
    private final ai.careerpilot.memory.CareerMemoryService careerMemory;

    public CareerContextRetriever(ResumeRepository resumes, JobRepository jobs,
                                  ApplicationRepository applications, WorkflowRunRepository workflowRuns,
                                  WorkflowService workflowService,
                                  DailyDiscoveryAnalyticsRepository dailyDiscoveryAnalytics,
                                  DailyCareerSummaryRepository dailyCareerSummaries,
                                  ai.careerpilot.repo.CandidateProfileRepository candidateProfiles,
                                  ai.careerpilot.memory.CareerMemoryService careerMemory) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.applications = applications;
        this.workflowRuns = workflowRuns;
        this.workflowService = workflowService;
        this.dailyDiscoveryAnalytics = dailyDiscoveryAnalytics;
        this.dailyCareerSummaries = dailyCareerSummaries;
        this.candidateProfiles = candidateProfiles;
        this.careerMemory = careerMemory;
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
        // Report the DERIVED lifecycle status (same value the UI shows), not the raw
        // persisted column — so the assistant never contradicts the pipeline the user sees.
        return new WorkflowContext(
                run.getThreadId(),
                workflowService.deriveDisplayStatus(run),
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

    /**
     * Phase 5P — the latest daily discovery agent output for this user, for the Copilot's
     * recommendations context. Returns {@code null} when the (dark-by-default) agent has never
     * run for this user, so handlers must treat it the same as "no data" rather than a fabricated
     * empty snapshot.
     */
    @Transactional(readOnly = true)
    public DailyDiscoveryContext getDailyDiscoveryContext(AuthenticatedUser user) {
        var latest = dailyDiscoveryAnalytics.findFirstByUserIdOrderByComputedAtDesc(user.userId()).orElse(null);
        if (latest == null) return null;
        var summary = dailyCareerSummaries.findFirstByUserIdOrderByCreatedAtDesc(user.userId()).orElse(null);
        return new DailyDiscoveryContext(
                latest.getComputedAt(),
                latest.getRecommendedJobs(), latest.getMustApplyJobs(), latest.getHighPriorityJobs(),
                latest.getHumanReviewJobs(),
                summary != null ? summary.getTopCompanies() : null,
                summary != null ? summary.getTopSkills() : null,
                summary != null ? summary.getSummaryText() : null,
                summary != null ? summary.getInterviewProbabilityDelta() : null,
                summary != null ? summary.getOfferProbabilityDelta() : null);
    }

    /**
     * The canonical {@link ai.careerpilot.domain.CandidateProfile} (Phase 1 — Candidate
     * Intelligence Profile), for skills the Copilot needs to know but shouldn't ask for again:
     * career goals, structured preferences, and the excluded-roles memory. Returns {@code null}
     * when {@code CANDIDATE_PROFILE_ENABLED} is off or no profile has been generated for this
     * user yet — callers must treat that the same as "no data" (same convention as
     * {@link #getDailyDiscoveryContext}), never a fabricated empty profile.
     */
    @Transactional(readOnly = true)
    public CandidateProfileContext getCandidateProfileContext(AuthenticatedUser user) {
        return candidateProfiles.findByUserId(user.userId())
                .map(p -> new CandidateProfileContext(
                        p.getCurrentRole(), p.getSeniorityLevel(), p.getYearsExperience(),
                        p.getTargetRolesJson(), p.getSkillsJson(), p.getCareerGoalsJson(),
                        p.getPreferredCountriesJson(), p.getPreferredCitiesJson(), p.getWorkModesJson(),
                        p.getVisaRequired(), p.getSalaryCurrency(), p.getSalaryTarget(),
                        p.getExcludedRolesJson()))
                .orElse(null);
    }

    /**
     * The top {@code limit} career decision memories relevant to this user — ranked, not a full
     * dump (see {@code CareerMemoryService.relevantFor}). Returns an empty list when
     * {@code career.memory.enabled} is off or nothing qualifies yet; callers must treat that the
     * same as "no memories", never fabricate one.
     */
    @Transactional(readOnly = true)
    public List<MemoryContext> getRelevantMemories(AuthenticatedUser user, int limit) {
        var ranked = careerMemory.relevantFor(user.userId(), limit);
        // Best-effort usage tracking, in its own transaction (REQUIRES_NEW) so it never touches
        // this method's read-only one — see CareerMemoryService.recordUsage.
        careerMemory.recordUsage(ranked.stream().map(CareerDecisionMemory::getId).toList());
        return ranked.stream()
                .map(m -> new MemoryContext(m.getDecisionType(), m.getCategory(), m.getValue(),
                        m.getReason(), m.getCreatedAt()))
                .toList();
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

    /** Canonical candidate profile fields the Copilot should never have to ask for again. */
    public record CandidateProfileContext(
            String currentRole,
            String seniorityLevel,
            Integer yearsExperience,
            String targetRolesJson,
            String skillsJson,
            String careerGoalsJson,
            String preferredCountriesJson,
            String preferredCitiesJson,
            String workModesJson,
            Boolean visaRequired,
            String salaryCurrency,
            java.math.BigDecimal salaryTarget,
            String excludedRolesJson) {}

    /** One ranked career decision memory, rendered for the Copilot — never the raw entity. */
    public record MemoryContext(
            String decisionType,
            String category,
            String value,
            String reason,
            java.time.Instant createdAt) {}

    public record DailyDiscoveryContext(
            java.time.Instant computedAt,
            Integer recommendedJobs,
            Integer mustApplyJobs,
            Integer highPriorityJobs,
            Integer humanReviewJobs,
            String topCompanies,
            String topSkills,
            String summaryText,
            java.math.BigDecimal interviewProbabilityDelta,
            java.math.BigDecimal offerProbabilityDelta) {}
}
