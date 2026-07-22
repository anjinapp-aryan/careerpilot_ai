package ai.careerpilot.applications;

import ai.careerpilot.applications.ApplicationHealthService.HealthResult;
import ai.careerpilot.applications.ApplicationNextActionService.NextAction;
import ai.careerpilot.applications.ApplicationRecommendationService.RecommendationResult;
import ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse;
import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.ApplicationStatusHistory;
import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ApplicationRetryRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.ApplicationReviewRepository;
import ai.careerpilot.repo.ApplicationStatusHistoryRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.CoverLetterRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application Command Center — assembles one {@link ApplicationCardResponse} per {@link Application}
 * row by joining, read-only, across every artifact the resume-tailoring/ATS/cover-letter/package/
 * review pipelines may have produced for that (user, job), plus the deterministic health/
 * recommendation/next-action engines. Nothing here writes to any table — {@code Application.matchScore}/
 * {@code atsScore} stay dead columns; fresh scores are computed at read-time instead (see CLAUDE.md's
 * "Provisioned-but-unused" section on why we don't trust those columns).
 */
@Service
public class ApplicationCardService {

    private final JobRepository jobs;
    private final JobRecommendationRepository jobRecommendations;
    private final ResumeTailoringRepository resumeTailorings;
    private final ResumeAtsAnalysisRepository atsAnalyses;
    private final CoverLetterRepository coverLetters;
    private final ApplicationPackageRepository applicationPackages;
    private final ApplicationReviewRepository applicationReviews;
    private final CandidateProfileRepository candidateProfiles;
    private final ApplicationLifecycleRepository lifecycles;
    private final ApplicationStatusHistoryRepository statusHistory;
    private final ApplicationHealthService healthService;
    private final ApplicationRecommendationService recommendationService;
    private final ApplicationNextActionService nextActionService;
    private final ApplicationExecutionRepository executions;
    private final ApplicationRetryRepository retries;

    public ApplicationCardService(JobRepository jobs,
                                  JobRecommendationRepository jobRecommendations,
                                  ResumeTailoringRepository resumeTailorings,
                                  ResumeAtsAnalysisRepository atsAnalyses,
                                  CoverLetterRepository coverLetters,
                                  ApplicationPackageRepository applicationPackages,
                                  ApplicationReviewRepository applicationReviews,
                                  CandidateProfileRepository candidateProfiles,
                                  ApplicationLifecycleRepository lifecycles,
                                  ApplicationStatusHistoryRepository statusHistory,
                                  ApplicationHealthService healthService,
                                  ApplicationRecommendationService recommendationService,
                                  ApplicationNextActionService nextActionService,
                                  ApplicationExecutionRepository executions,
                                  ApplicationRetryRepository retries) {
        this.jobs = jobs;
        this.jobRecommendations = jobRecommendations;
        this.resumeTailorings = resumeTailorings;
        this.atsAnalyses = atsAnalyses;
        this.coverLetters = coverLetters;
        this.applicationPackages = applicationPackages;
        this.applicationReviews = applicationReviews;
        this.candidateProfiles = candidateProfiles;
        this.lifecycles = lifecycles;
        this.statusHistory = statusHistory;
        this.healthService = healthService;
        this.recommendationService = recommendationService;
        this.nextActionService = nextActionService;
        this.executions = executions;
        this.retries = retries;
    }

    public List<ApplicationCardResponse> assembleAll(UUID userId, List<Application> applications) {
        return applications.stream().map(a -> assemble(userId, a)).toList();
    }

    public ApplicationCardResponse assemble(UUID userId, Application app) {
        Job job = jobs.findById(app.getJobId()).orElse(null);

        Integer matchScore = app.getMatchScore();
        Integer atsScore = app.getAtsScore();

        var reco = jobRecommendations.findByUserIdAndJobId(userId, app.getJobId()).orElse(null);
        if (matchScore == null && reco != null) matchScore = reco.getMatchScore();

        boolean resumeTailored = resumeTailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, app.getJobId()).isPresent();

        var latestAts = atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, app.getJobId()).orElse(null);
        if (atsScore == null && latestAts != null) atsScore = latestAts.getAtsScore();

        boolean coverLetterReady = coverLetters.findByUserIdAndJobId(userId, app.getJobId()).isPresent();

        ApplicationPackage pkg = applicationPackages.findByUserIdAndJobId(userId, app.getJobId()).orElse(null);
        boolean applicationPackageReady = pkg != null;
        boolean applicationReviewReady = pkg != null && applicationReviews.findByApplicationPackageId(pkg.getId()).isPresent();

        CandidateProfile profile = candidateProfiles.findByUserId(userId).orElse(null);
        Boolean visaRequired = profile == null ? null : profile.getVisaRequired();

        // Phase 3A lifecycle (dark unless workflow.tracking.enabled — findByUserIdAndJobId then returns empty).
        Optional<ApplicationLifecycle> lifecycle = lifecycles.findByUserIdAndJobId(userId, app.getJobId());
        String lifecycleStatus = lifecycle.map(ApplicationLifecycle::getCurrentStatus).orElse(null);
        Instant lastStatusChangeAt = lifecycle
                .map(l -> {
                    List<ApplicationStatusHistory> history = statusHistory.findByLifecycleIdOrderByChangedAtDesc(l.getId());
                    return history.isEmpty() ? l.getUpdatedAt() : history.get(0).getChangedAt();
                })
                .orElse(null);

        // Refresh the app's computed match/ATS onto a shallow copy so health/recommendation see fresh values
        // without ever writing them back to the dead columns.
        Application scored = Application.builder()
                .id(app.getId()).userId(app.getUserId()).orgId(app.getOrgId()).jobId(app.getJobId())
                .resumeId(app.getResumeId()).status(app.getStatus()).matchScore(matchScore).atsScore(atsScore)
                .nextAction(app.getNextAction()).nextActionAt(app.getNextActionAt()).notes(app.getNotes())
                .favorite(app.getFavorite()).priority(app.getPriority()).archived(app.getArchived())
                .createdAt(app.getCreatedAt()).updatedAt(app.getUpdatedAt())
                .build();

        HealthResult health = healthService.evaluate(scored, lastStatusChangeAt);
        RecommendationResult recommendation = recommendationService.recommend(scored, health, lastStatusChangeAt);
        NextAction nextAction = nextActionService.suggest(lifecycleStatus, app.getStatus());

        // Phase 7.16.3 — Automation Recovery Center visibility. Dark unless application.execution.enabled
        // (findFirstByUserIdAndJobIdOrderByCreatedAtDesc then returns empty); retryCount counts only
        // against the LATEST attempt row, not the cumulative retry chain, since each retry creates a
        // new ApplicationExecution row (see ApplicationExecution's class javadoc).
        ApplicationExecution latestExecution = executions
                .findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, app.getJobId())
                .orElse(null);
        String executionStatus = latestExecution == null ? null : latestExecution.getExecutionStatus();
        String automationHealth = deriveAutomationHealth(latestExecution);
        Integer retryCount = latestExecution == null ? null
                : (int) retries.countByApplicationExecutionId(latestExecution.getId());
        String verificationStatus = latestExecution == null ? null : latestExecution.getVerificationStatus();

        return new ApplicationCardResponse(
                app.getId(), app.getJobId(), app.getResumeId(), app.getStatus(), matchScore, atsScore,
                app.getNextAction(), app.getNextActionAt(), app.getNotes(),
                Boolean.TRUE.equals(app.getFavorite()), app.getPriority() == null ? "MEDIUM" : app.getPriority(),
                Boolean.TRUE.equals(app.getArchived()), app.getCreatedAt(), app.getUpdatedAt(),
                job == null ? null : job.getTitle(),
                job == null ? null : job.getCompany(),
                job == null ? null : job.getLocation(),
                job == null ? null : job.getSalaryRange(),
                job == null ? null : job.getRemoteType(),
                job == null ? null : job.getSource(),
                job == null ? null : job.getExternalUrl(),
                job == null ? null : job.getSponsorshipAvailable(),
                resumeTailored, latestAts != null, coverLetterReady, applicationPackageReady, applicationReviewReady,
                visaRequired,
                health.status(), health.score(), health.reasoning(),
                recommendation.action(), recommendation.reasoning(),
                nextAction.action(), nextAction.suggestedAt(),
                lifecycleStatus,
                latestExecution == null ? null : latestExecution.getId(),
                executionStatus, automationHealth, retryCount, verificationStatus
        );
    }

    /**
     * Phase 7.16.3 — a derived display label for the Recovery Dashboard, computed from {@code
     * ApplicationExecution} fields rather than persisted, mirroring {@code
     * WorkflowService#deriveDisplayStatus}'s "derive, don't trust a lagging column" convention.
     */
    private static String deriveAutomationHealth(ApplicationExecution exec) {
        if (exec == null) return null;
        return switch (exec.getExecutionStatus()) {
            case ApplicationExecution.STATUS_QUEUED, ApplicationExecution.STATUS_VALIDATING,
                    ApplicationExecution.STATUS_EXECUTING -> "RUNNING";
            case ApplicationExecution.STATUS_AWAITING_APPROVAL -> "WAITING";
            case ApplicationExecution.STATUS_RETRY -> "RETRYING";
            case ApplicationExecution.STATUS_MANUAL_REVIEW -> "MANUAL_REVIEW";
            case ApplicationExecution.STATUS_RETRIED -> "RETRYING";
            case ApplicationExecution.STATUS_SUBMITTED -> exec.getRetryOfExecutionId() != null
                    ? "RECOVERED"
                    : (exec.getVerificationStatus() == null || "VERIFIED".equals(exec.getVerificationStatus())
                            ? "COMPLETED" : "VERIFICATION_FAILED");
            case ApplicationExecution.STATUS_ABORTED, ApplicationExecution.STATUS_FAILED -> "FAILED";
            default -> null;
        };
    }
}
