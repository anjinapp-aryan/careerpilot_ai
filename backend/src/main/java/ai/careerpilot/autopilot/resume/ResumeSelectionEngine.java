package ai.careerpilot.autopilot.resume;

import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.repo.ResumeLearningRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.2 — deterministically chooses the best existing tailored resume version for a (user, job),
 * or signals that the 7.3 tailoring pipeline must run first. Reuses the Phase 2D tailoring history
 * ({@link ResumeTailoringRepository}) and the Phase 6.5 resume-learning best-version signal
 * ({@link ResumeLearningRepository}); it never generates, edits, or persists a resume itself.
 *
 * <p>The picking rule lives in the pure {@link #evaluate} (unit-testable without mocks). Fails safe:
 * no base resume ⇒ {@code NO_BASE_RESUME}; no tailored version, or the best available ATS is below
 * the suitability floor, ⇒ {@code NEEDS_TAILORING} (so the agent regenerates rather than applying
 * with a weak resume). Flag-gated by {@code resume.selection.enabled} (default off).
 */
@Service
public class ResumeSelectionEngine {

    private static final Logger log = LoggerFactory.getLogger(ResumeSelectionEngine.class);

    private final ResumeRepository resumes;
    private final ResumeTailoringRepository tailorings;
    private final ResumeLearningRepository resumeLearning;
    private final boolean enabled;
    private final int atsFloor;

    public ResumeSelectionEngine(ResumeRepository resumes, ResumeTailoringRepository tailorings,
                                 ResumeLearningRepository resumeLearning,
                                 @Value("${resume.selection.enabled:false}") boolean enabled,
                                 @Value("${resume.selection.ats-floor:70}") int atsFloor) {
        this.resumes = resumes;
        this.tailorings = tailorings;
        this.resumeLearning = resumeLearning;
        this.enabled = enabled;
        this.atsFloor = atsFloor;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The chosen version plus why. {@code tailoringId} is set only for {@code SELECTED}. */
    public record ResumeSelection(SelectionOutcome outcome, UUID tailoringId, Integer atsScore, String reason) {}

    /**
     * Pure selection over the per-job tailoring history. {@code bestLearnedVersion} is the Phase 6.5
     * globally best-performing version id (nullable) — used only to enrich the reason when the chosen
     * version matches it. Best = highest {@code atsAfter} (unknown ATS sorts lowest), tie-broken by
     * newest {@code tailoringVersion}.
     */
    public ResumeSelection evaluate(boolean hasBaseResume, List<ResumeTailoring> tailoredVersions,
                                    String bestLearnedVersion) {
        if (!hasBaseResume) {
            return new ResumeSelection(SelectionOutcome.NO_BASE_RESUME, null, null,
                    "No base resume on file — upload one before the agent can tailor and apply.");
        }
        if (tailoredVersions == null || tailoredVersions.isEmpty()) {
            return new ResumeSelection(SelectionOutcome.NEEDS_TAILORING, null, null,
                    "No tailored resume for this job yet — tailoring pipeline required.");
        }

        ResumeTailoring best = tailoredVersions.stream()
                .max(Comparator.comparing((ResumeTailoring t) -> t.getAtsAfter() == null ? Integer.MIN_VALUE : t.getAtsAfter())
                        .thenComparing(t -> t.getTailoringVersion() == null ? 0 : t.getTailoringVersion()))
                .orElseThrow();
        Integer bestAts = best.getAtsAfter();

        if (bestAts == null || bestAts < atsFloor) {
            return new ResumeSelection(SelectionOutcome.NEEDS_TAILORING, null, bestAts,
                    "Best available tailored version ATS " + (bestAts == null ? "unknown" : bestAts)
                            + " is below the suitability floor of " + atsFloor + " — regenerate.");
        }

        boolean matchesLearnedBest = bestLearnedVersion != null && bestLearnedVersion.equals(best.getId().toString());
        String reason = "Selected tailored version v" + best.getTailoringVersion() + " (ATS " + bestAts + ")"
                + (matchesLearnedBest ? "; matches your historically best-performing resume." : ".");
        return new ResumeSelection(SelectionOutcome.SELECTED, best.getId(), bestAts, reason);
    }

    /** Select for a (user, job). Empty when disabled. Never throws. */
    @Transactional(readOnly = true)
    public Optional<ResumeSelection> select(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        boolean hasBaseResume = !resumes.findByUserIdOrderByCreatedAtDesc(userId).isEmpty();
        List<ResumeTailoring> versions = tailorings.findByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId);
        String bestLearned = resumeLearning.findByUserIdAndBestVersionTrue(userId)
                .map(ResumeLearning::getResumeVersion).orElse(null);
        ResumeSelection selection = evaluate(hasBaseResume, versions, bestLearned);
        log.info("RESUME_SELECTION user={} job={} outcome={} tailoringId={} ats={}",
                userId, jobId, selection.outcome(), selection.tailoringId(), selection.atsScore());
        return Optional.of(selection);
    }
}
