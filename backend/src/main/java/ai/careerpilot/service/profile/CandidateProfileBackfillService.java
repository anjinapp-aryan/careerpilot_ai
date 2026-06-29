package ai.careerpilot.service.profile;

import ai.careerpilot.repo.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * One-time enablement backfill: generate a canonical Candidate Profile for every existing user who
 * has a resume but no (or a stale) profile, so that flipping {@code jobs.matching.profile-source-enabled}
 * on doesn't silently degrade recommendations for users who haven't re-uploaded since the feature
 * shipped.
 *
 * <p>Idempotent (skip-if-current via {@link CandidateProfileService#backfillUser}), failure-isolated
 * (one user's extraction failure never aborts the batch), and throttled between LLM calls to respect
 * the shared provider rate limits. The dry-run path calls no LLM — it only sizes the work.
 */
@Service
public class CandidateProfileBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CandidateProfileBackfillService.class);

    private final ResumeRepository resumes;
    private final CandidateProfileService profileService;
    private final long throttleMs;

    public CandidateProfileBackfillService(ResumeRepository resumes,
                                           CandidateProfileService profileService,
                                           @Value("${candidate.profile.backfill.throttle-ms:500}") long throttleMs) {
        this.resumes = resumes;
        this.profileService = profileService;
        this.throttleMs = throttleMs;
    }

    /** Summary of a backfill run. {@code candidates} = users that would be (or were) processed. */
    public record BackfillReport(boolean dryRun, int totalUsersWithResume, int candidates,
                                 int generated, int skippedCurrent, int skippedNoResume, int failed) {}

    /**
     * Run (or simulate) the backfill across all users with a resume.
     *
     * @param dryRun when true, performs no LLM calls — only reports how many users need a profile.
     */
    public BackfillReport run(boolean dryRun) {
        List<UUID> userIds = resumes.findDistinctUserIds();
        int total = userIds.size();

        if (dryRun) {
            int candidates = (int) userIds.stream().filter(profileService::needsBackfill).count();
            log.info("PROFILE_BACKFILL dryRun usersWithResume={} candidates={}", total, candidates);
            return new BackfillReport(true, total, candidates, 0, 0, 0, 0);
        }

        int generated = 0, skippedCurrent = 0, skippedNoResume = 0, failed = 0;
        for (UUID userId : userIds) {
            CandidateProfileService.BackfillOutcome outcome;
            try {
                outcome = profileService.backfillUser(userId);
            } catch (Exception e) {                       // defence-in-depth; service already isolates
                log.warn("PROFILE_BACKFILL user={} unexpected error: {}", userId, e.toString());
                outcome = CandidateProfileService.BackfillOutcome.FAILED;
            }
            switch (outcome) {
                case GENERATED -> { generated++; throttle(); }   // only sleep after a real LLM call
                case SKIPPED_CURRENT -> skippedCurrent++;
                case SKIPPED_NO_RESUME -> skippedNoResume++;
                case FAILED -> failed++;
            }
        }
        int candidates = generated + failed;
        log.info("PROFILE_BACKFILL done usersWithResume={} generated={} skippedCurrent={} skippedNoResume={} failed={}",
                total, generated, skippedCurrent, skippedNoResume, failed);
        return new BackfillReport(false, total, candidates, generated, skippedCurrent, skippedNoResume, failed);
    }

    /**
     * Weekly scheduled catch-up rebuild (Phase 1.5): same idempotent skip-if-current logic as
     * {@link #run}, but bounded to at most {@code maxGenerated} real LLM extractions per run so a
     * large user base can't turn one run into an unbounded batch. Stops scanning once the cap is
     * hit — any remaining stale users are simply picked up by next week's run (the fingerprint
     * check makes this resumable across runs with no extra state). Records {@link
     * CandidateProfileService#REASON_SCHEDULED_REBUILD} instead of {@code MANUAL_REBUILD} so the
     * version history can distinguish scheduled catch-up from an explicit user/admin action.
     */
    public BackfillReport runCapped(int maxGenerated) {
        List<UUID> userIds = resumes.findDistinctUserIds();
        int total = userIds.size();

        int generated = 0, skippedCurrent = 0, skippedNoResume = 0, failed = 0;
        for (UUID userId : userIds) {
            if (generated >= maxGenerated) break;
            CandidateProfileService.BackfillOutcome outcome;
            try {
                outcome = profileService.backfillUser(userId, CandidateProfileService.REASON_SCHEDULED_REBUILD);
            } catch (Exception e) {                       // defence-in-depth; service already isolates
                log.warn("PROFILE_SCHEDULED_REBUILD user={} unexpected error: {}", userId, e.toString());
                outcome = CandidateProfileService.BackfillOutcome.FAILED;
            }
            switch (outcome) {
                case GENERATED -> { generated++; throttle(); }   // only sleep after a real LLM call
                case SKIPPED_CURRENT -> skippedCurrent++;
                case SKIPPED_NO_RESUME -> skippedNoResume++;
                case FAILED -> failed++;
            }
        }
        int candidates = generated + failed;
        log.info("PROFILE_SCHEDULED_REBUILD done usersWithResume={} generated={} skippedCurrent={} skippedNoResume={} failed={} cap={}",
                total, generated, skippedCurrent, skippedNoResume, failed, maxGenerated);
        return new BackfillReport(false, total, candidates, generated, skippedCurrent, skippedNoResume, failed);
    }

    private void throttle() {
        if (throttleMs <= 0) return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
