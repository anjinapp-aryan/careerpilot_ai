package ai.careerpilot.submission.reuse;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether {@link ai.careerpilot.submission.ApplicationSubmissionSessionService}'s Step 6
 * (field mapping + 11 common-question AI-generated answers) can copy a prior session's answers
 * for the same (user, job) instead of calling the AI Gateway again — the one genuinely expensive,
 * unconditionally-repeated piece of work in the pipeline (validation is structural/cheap; resume
 * tailoring, cover letter, package assembly and review already reuse via their own {@code
 * latest().or(...)} guards).
 *
 * <p>Pure decision logic, no I/O beyond the two read-only repository lookups below — same
 * discipline as {@code RetryPolicyService}/{@code VerificationAdjudicator} elsewhere in this
 * codebase. Never mutates a session; the caller applies the decision.
 *
 * <p><b>What "compatible" means here.</b> The prior session's linked resumeTailoringId,
 * applicationPackageId, companyKnowledgeId and starStoryId must all equal the current session's —
 * any one differing means something that could change an answer's content changed, so the
 * answers must be regenerated. A profile edit is caught transitively: {@code
 * ApplicationPackageService} bumps the package version (and therefore packageId) when the
 * candidate profile it draws from changes, so comparing packageId alone already covers it without
 * this resolver needing its own profile-version tracking.
 */
@Service
public class ApplicationReuseResolver {

    /** Answers older than this are rebuilt even if every linked artifact still matches. */
    private final Duration ttl;
    private final ApplicationSubmissionSessionRepository sessions;
    private final ApplicationSubmissionAnswerRepository answers;

    public ApplicationReuseResolver(ApplicationSubmissionSessionRepository sessions,
                                    ApplicationSubmissionAnswerRepository answers,
                                    @Value("${application.submission.reuse.ttl-hours:24}") long ttlHours) {
        this.sessions = sessions;
        this.answers = answers;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public record Basis(UUID resumeTailoringId, UUID applicationPackageId,
                        UUID companyKnowledgeId, UUID starStoryId) {}

    public record Decision(AnswerReuseDecision reason, Optional<ApplicationSubmissionSession> sourceSession) {
        public static Decision fullBuild() {
            return new Decision(AnswerReuseDecision.FULL_BUILD, Optional.empty());
        }
        public static Decision rebuilt(AnswerReuseDecision reason) {
            return new Decision(reason, Optional.empty());
        }
        public static Decision reused(ApplicationSubmissionSession source) {
            return new Decision(AnswerReuseDecision.REUSED, Optional.of(source));
        }
    }

    /**
     * @param currentSessionId excluded from the candidate search — a session is never compared
     *                         against itself.
     */
    public Decision resolve(UUID userId, UUID jobId, UUID currentSessionId, Basis current) {
        List<ApplicationSubmissionSession> history = sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);

        ApplicationSubmissionSession candidate = history.stream()
                .filter(s -> !s.getId().equals(currentSessionId))
                .filter(s -> s.getAnswersReuseDecision() != null) // Step 6 genuinely ran on it
                .findFirst()
                .orElse(null);
        if (candidate == null) return Decision.fullBuild();

        Instant candidateAnsweredAt = candidate.getUpdatedAt() != null ? candidate.getUpdatedAt() : candidate.getCreatedAt();
        if (candidateAnsweredAt == null || candidateAnsweredAt.isBefore(Instant.now().minus(ttl))) {
            return Decision.rebuilt(AnswerReuseDecision.REBUILT_EXPIRED);
        }

        if (!Objects.equals(candidate.getResumeTailoringId(), current.resumeTailoringId())) {
            return Decision.rebuilt(AnswerReuseDecision.REBUILT_RESUME_CHANGED);
        }
        if (!Objects.equals(candidate.getApplicationPackageId(), current.applicationPackageId())) {
            return Decision.rebuilt(AnswerReuseDecision.REBUILT_PACKAGE_CHANGED);
        }
        if (!Objects.equals(candidate.getCompanyKnowledgeId(), current.companyKnowledgeId())
                || !Objects.equals(candidate.getStarStoryId(), current.starStoryId())) {
            return Decision.rebuilt(AnswerReuseDecision.REBUILT_CONTEXT_CHANGED);
        }

        if (answers.findBySessionIdOrderByCreatedAtAsc(candidate.getId()).isEmpty()) {
            // Compatible on every id but somehow has no answer rows (e.g. an old row from before
            // this reuse layer existed, or every question failed) — nothing to copy, must build.
            return Decision.fullBuild();
        }
        return Decision.reused(candidate);
    }
}
