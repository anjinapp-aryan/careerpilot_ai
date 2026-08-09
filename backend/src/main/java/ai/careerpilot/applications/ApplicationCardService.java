package ai.careerpilot.applications;

import ai.careerpilot.applications.ApplicationHealthService.HealthResult;
import ai.careerpilot.applications.ApplicationNextActionService.NextAction;
import ai.careerpilot.applications.ApplicationRecommendationService.RecommendationResult;
import ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse;
import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ApplicationRetryRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.ApplicationReviewRepository;
import ai.careerpilot.repo.ApplicationStatusHistoryRepository;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.CoverLetterRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final ApplicationSubmissionSessionRepository submissionSessions;

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
                                  ApplicationRetryRepository retries,
                                  ApplicationSubmissionSessionRepository submissionSessions) {
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
        this.submissionSessions = submissionSessions;
    }

    /**
     * Assemble one card per application using a <b>fixed</b> number of queries, independent of how
     * many applications there are.
     *
     * <p><b>Why this is batched.</b> This used to be {@code applications.stream().map(a ->
     * assemble(userId, a))}, and {@link #assemble} issues roughly a dozen lookups. Against the
     * remote (Neon) database that is a dozen network round-trips per row: a single card was measured
     * at ~5.6s, so a real account with 20 applications could not finish inside any client timeout —
     * {@code GET /api/applications/cards} simply hung. Prefetching once and assembling in memory
     * removes the row multiplier entirely.
     */
    public List<ApplicationCardResponse> assembleAll(UUID userId, List<Application> applications) {
        if (applications.isEmpty()) return List.of();
        JoinedData data = prefetch(userId, applications);
        return applications.stream().map(a -> assemble(a, data)).toList();
    }

    /**
     * Single-card assembly. Deliberately routed through the same {@link #prefetch}/{@link
     * #assemble(Application, JoinedData)} pair as {@link #assembleAll} rather than keeping a second
     * per-row implementation — one code path means the list and the detail view cannot drift.
     */
    public ApplicationCardResponse assemble(UUID userId, Application app) {
        return assemble(app, prefetch(userId, List.of(app)));
    }

    private ApplicationCardResponse assemble(Application app, JoinedData data) {
        UUID jobId = app.getJobId();
        Job job = data.jobs().get(jobId);

        Integer matchScore = app.getMatchScore();
        Integer atsScore = app.getAtsScore();

        Integer recoScore = data.recommendationScores().get(jobId);
        if (matchScore == null && recoScore != null) matchScore = recoScore;

        boolean resumeTailored = data.tailoredJobIds().contains(jobId);

        Integer latestAtsScore = data.latestAtsScores().get(jobId);
        boolean atsAnalysisReady = data.latestAtsScores().containsKey(jobId);
        if (atsScore == null && latestAtsScore != null) atsScore = latestAtsScore;

        boolean coverLetterReady = data.coverLetterJobIds().contains(jobId);

        UUID packageId = data.packageIdsByJob().get(jobId);
        boolean applicationPackageReady = packageId != null;
        boolean applicationReviewReady = packageId != null && data.reviewedPackageIds().contains(packageId);

        Boolean visaRequired = data.profile() == null ? null : data.profile().getVisaRequired();

        // Phase 3A lifecycle (dark unless workflow.tracking.enabled — the lifecycle map is then empty).
        Optional<ApplicationLifecycle> lifecycle = Optional.ofNullable(data.lifecycles().get(jobId));
        String lifecycleStatus = lifecycle.map(ApplicationLifecycle::getCurrentStatus).orElse(null);
        Instant lastStatusChangeAt = lifecycle
                .map(l -> {
                    Instant latestChange = data.lastStatusChangeByLifecycle().get(l.getId());
                    return latestChange == null ? l.getUpdatedAt() : latestChange;
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
        // (the execution map is then empty); retryCount counts only against the LATEST attempt row,
        // not the cumulative retry chain, since each retry creates a new ApplicationExecution row
        // (see ApplicationExecution's class javadoc).
        ApplicationExecution latestExecution = data.latestExecutions().get(jobId);
        String executionStatus = latestExecution == null ? null : latestExecution.getExecutionStatus();
        String automationHealth = deriveAutomationHealth(latestExecution);
        Integer retryCount = latestExecution == null ? null
                : data.retryCounts().getOrDefault(latestExecution.getId(), 0L).intValue();
        String verificationStatus = latestExecution == null ? null : latestExecution.getVerificationStatus();

        boolean guidedApplyRequired = "GUIDED_APPLY_REQUIRED".equals(automationHealth);
        // Guided Apply Hardening — the ApplicationExecution row that drives automationHealth and the
        // ApplicationSubmissionSession row the candidate confirms manual submission on are two
        // independent entities (the execution-scoped Phase 2E path vs the orchestrated Phase 7.16
        // pipeline — deliberately separate per this codebase's own convention, see CLAUDE.md). Without
        // this check, an ABORTED execution keeps reporting "Guided Apply Required" forever even after
        // the user has already confirmed they submitted it manually — a genuinely stale UI state.
        // Final Hardening Pass — widened from "USER_REPORTED_SUBMITTED only" to every status
        // ai.careerpilot.submission.SubmissionStateMachine#resolvesManualCompletion recognises as
        // no-longer-needing manual completion (derived from the transition graph itself, not a
        // second hand-maintained list — see that method's own javadoc for the exact reasoning).
        // Covers the case this codebase's own docs call out (Phase 0's SUBMIT_UNVERIFIED,
        // "the application probably does exist, so it must be tracked") where a later, genuinely
        // automated attempt succeeded (or submitted-but-unverified) for the same job after an
        // earlier execution had aborted — the stale banner must clear there too.
        ApplicationSubmissionSession latestSubmission = data.latestSubmissionSessions().get(jobId);
        boolean alreadyReportedSubmitted = latestSubmission != null
                && ai.careerpilot.submission.SubmissionStateMachine.resolvesManualCompletion(latestSubmission.getStatus());
        if (alreadyReportedSubmitted) guidedApplyRequired = false;

        String blockerReason = guidedApplyRequired
                ? ai.careerpilot.domain.GuidedApplyReason.fromFailureReason(latestExecution.getFailureReason()).name()
                : null;
        String blockerDetail = guidedApplyRequired ? latestExecution.getFailureReason() : null;

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
                job == null ? null : job.getExternalId(),
                resumeTailored, atsAnalysisReady, coverLetterReady, applicationPackageReady, applicationReviewReady,
                visaRequired,
                health.status(), health.score(), health.reasoning(),
                recommendation.action(), recommendation.reasoning(),
                nextAction.action(), nextAction.suggestedAt(),
                lifecycleStatus,
                latestExecution == null ? null : latestExecution.getId(),
                executionStatus, automationHealth, retryCount, verificationStatus,
                guidedApplyRequired, blockerReason, blockerDetail
        );
    }

    /**
     * Everything {@link #assemble(Application, JoinedData)} joins against, loaded up front. Each
     * field is keyed so lookup is a map hit rather than a query; presence-only joins are sets, and
     * the two "latest row per job" joins keep the same ordering their per-row finders use.
     */
    private record JoinedData(Map<UUID, Job> jobs,
                              Map<UUID, Integer> recommendationScores,
                              Set<UUID> tailoredJobIds,
                              Map<UUID, Integer> latestAtsScores,
                              Set<UUID> coverLetterJobIds,
                              Map<UUID, UUID> packageIdsByJob,
                              Set<UUID> reviewedPackageIds,
                              CandidateProfile profile,
                              Map<UUID, ApplicationLifecycle> lifecycles,
                              Map<UUID, Instant> lastStatusChangeByLifecycle,
                              Map<UUID, ApplicationExecution> latestExecutions,
                              Map<UUID, Long> retryCounts,
                              Map<UUID, ApplicationSubmissionSession> latestSubmissionSessions) {
    }

    /**
     * One query per join, regardless of how many applications are being assembled.
     *
     * <p>The dependent joins (review-by-package, retries-by-execution, history-by-lifecycle) are
     * skipped entirely when their parent set is empty — most of them are dark by default, so the
     * common case issues fewer queries than the field count suggests, and an {@code IN ()} with no
     * values is never generated.
     */
    private JoinedData prefetch(UUID userId, List<Application> applications) {
        Set<UUID> jobIds = applications.stream().map(Application::getJobId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<UUID, Job> jobsById = jobs.findAllById(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, j -> j, (a, b) -> a));

        Map<UUID, Integer> recoScores = jobRecommendations.findByUserIdAndJobIdIn(userId, jobIds).stream()
                .collect(Collectors.toMap(JobRecommendation::getJobId, JobRecommendation::getMatchScore, (a, b) -> a));

        Set<UUID> tailored = Set.copyOf(resumeTailorings.findTailoredJobIds(userId, jobIds));

        Map<UUID, Integer> atsScores = new HashMap<>();
        for (var row : atsAnalyses.findLatestScoresByJob(userId, jobIds)) {
            atsScores.put(row.getJobId(), row.getAtsScore());
        }

        Set<UUID> withCoverLetter = Set.copyOf(coverLetters.findJobIdsWithCoverLetter(userId, jobIds));

        Map<UUID, UUID> packageIdsByJob = applicationPackages.findRefsByUserIdAndJobIdIn(userId, jobIds).stream()
                .collect(Collectors.toMap(ApplicationPackageRepository.PackageRef::getJobId,
                        ApplicationPackageRepository.PackageRef::getId, (a, b) -> a));
        Set<UUID> reviewed = packageIdsByJob.isEmpty()
                ? Set.of()
                : Set.copyOf(applicationReviews.findReviewedPackageIds(packageIdsByJob.values()));

        CandidateProfile profile = candidateProfiles.findByUserId(userId).orElse(null);

        Map<UUID, ApplicationLifecycle> lifecyclesByJob = lifecycles.findByUserId(userId).stream()
                .filter(l -> jobIds.contains(l.getJobId()))
                .collect(Collectors.toMap(ApplicationLifecycle::getJobId, l -> l, (a, b) -> a));
        Map<UUID, Instant> lastChange = new HashMap<>();
        if (!lifecyclesByJob.isEmpty()) {
            Set<UUID> lifecycleIds = lifecyclesByJob.values().stream()
                    .map(ApplicationLifecycle::getId).collect(Collectors.toSet());
            for (var row : statusHistory.findLatestChangePerLifecycle(lifecycleIds)) {
                lastChange.put(row.getLifecycleId(), row.getChangedAt());
            }
        }

        Map<UUID, ApplicationExecution> latestExecutions = executions.findLatestPerJob(userId, jobIds).stream()
                .collect(Collectors.toMap(ApplicationExecution::getJobId, e -> e, (a, b) -> a));
        Map<UUID, Long> retryCounts = new HashMap<>();
        if (!latestExecutions.isEmpty()) {
            Set<UUID> executionIds = latestExecutions.values().stream()
                    .map(ApplicationExecution::getId).collect(Collectors.toSet());
            for (var row : retries.countPerExecution(executionIds)) {
                retryCounts.put(row.getExecutionId(), row.getCnt());
            }
        }

        // Guided Apply Hardening — latest ApplicationSubmissionSession per job, same "fetch this
        // user's whole set once, group in memory" convention as lifecyclesByJob above (that pattern
        // already proved N+1-safe for this exact file). findByUserIdOrderByCreatedAtDesc is already
        // DESC-sorted, so the first occurrence per jobId in iteration order is genuinely the latest —
        // no extra sort needed.
        Map<UUID, ApplicationSubmissionSession> latestSubmissionByJob = submissionSessions
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(s -> jobIds.contains(s.getJobId()))
                .collect(Collectors.toMap(ApplicationSubmissionSession::getJobId, s -> s, (a, b) -> a));

        return new JoinedData(jobsById, recoScores, tailored, atsScores, withCoverLetter,
                packageIdsByJob, reviewed, profile, lifecyclesByJob, lastChange,
                latestExecutions, retryCounts, latestSubmissionByJob);
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
            // Guided Apply — ABORTED means automation deliberately did not attempt/continue
            // (CAPTCHA, ineligible connector, no backend configured, rollout gate, package not
            // assembled — every abort site reviewed in the Guided Apply audit) — never a technical
            // failure. FAILED alone (a thrown exception mid-attempt) keeps that label.
            case ApplicationExecution.STATUS_ABORTED -> "GUIDED_APPLY_REQUIRED";
            case ApplicationExecution.STATUS_FAILED -> "FAILED";
            default -> null;
        };
    }
}
