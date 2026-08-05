package ai.careerpilot.employerquestion;

import ai.careerpilot.domain.EmployerAnswer;
import ai.careerpilot.domain.EmployerQuestion;
import ai.careerpilot.repo.EmployerAnswerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase D — reusable answers, and the human review workflow that makes them reusable.
 *
 * <p><b>The invariant this service exists to hold:</b> an answer becomes usable by automation only
 * when a human has approved that exact text. Drafting, storing and approving are three separate
 * acts, and only the third one grants usability.
 *
 * <p><b>Drafts are never created during resolution.</b> {@link #resolve} is a pure read — it will
 * return "no verified answer" all day rather than quietly generating one, because a draft created
 * mid-submission is a draft nobody reviews. Drafting is an explicit call to {@link #draft}, made
 * from a review surface, never from an automation path.
 *
 * <p>Gated by {@code employer.question.intelligence.enabled} (default {@code false}).
 */
@Service
public class EmployerAnswerService {

    private static final Logger log = LoggerFactory.getLogger(EmployerAnswerService.class);

    private final EmployerAnswerRepository answers;
    private final EmployerQuestionService questions;
    private final boolean enabled;

    public EmployerAnswerService(EmployerAnswerRepository answers, EmployerQuestionService questions,
                                 @Value("${employer.question.intelligence.enabled:false}") boolean enabled) {
        this.answers = answers;
        this.questions = questions;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The reuse path. Matches the question against the library and returns the stored answer with a
     * full explanation of why — including when it returns nothing.
     *
     * <p>Never writes an answer. It does record usage on a successful reuse, which is bookkeeping
     * about an existing approved answer rather than the creation of a new one.
     */
    @Transactional
    public AnswerResolution resolve(UUID userId, String questionText, String canonicalField) {
        if (!enabled) {
            return AnswerResolution.none("employer question intelligence is disabled");
        }
        if (userId == null || questionText == null || questionText.isBlank()) {
            return AnswerResolution.none("no user or question text supplied");
        }

        Optional<QuestionMatchingEngine.Match> match = questions.find(questionText, canonicalField);
        if (match.isEmpty()) {
            return AnswerResolution.none(
                    "No verified answer available. This question has not been seen before, "
                            + "so it needs a human-reviewed answer.");
        }

        EmployerQuestion question = match.get().question();
        Optional<EmployerAnswer> stored = answers.findByUserIdAndQuestionId(userId, question.getId());
        if (stored.isEmpty()) {
            return AnswerResolution.none("Question recognised (" + match.get().how()
                    + ") but this candidate has no answer for it yet.");
        }

        EmployerAnswer answer = stored.get();
        AnswerConfidence confidence = AnswerConfidence.parseOrUnknown(answer.getConfidence());

        if (!answer.isApproved()) {
            return new AnswerResolution(null, confidence, false, null,
                    answer.getSource(),
                    "A draft answer exists but no human has approved it, so it may not be used.",
                    question.getId());
        }
        if (!confidence.isUsableByAutomation()) {
            // Belt and braces: approval and confidence are separate gates, and an approved answer
            // still carrying an untrusted band is a data inconsistency worth refusing rather than
            // silently trusting.
            return new AnswerResolution(null, confidence, true, answer.getApprovedAt(),
                    answer.getSource(),
                    "Answer is approved but its confidence band (" + confidence
                            + ") is not usable by automation.",
                    question.getId());
        }

        answer.setUsageCount(answer.getUsageCount() + 1);
        answer.setLastUsedAt(Instant.now());
        answer.setUpdatedAt(Instant.now());
        answers.save(answer);

        return new AnswerResolution(answer.getAnswerText(), confidence, true, answer.getApprovedAt(),
                answer.getSource(),
                "Reused a human-approved answer (" + match.get().how() + "), approved "
                        + answer.getApprovedAt() + ", used " + answer.getUsageCount() + " time(s).",
                question.getId());
    }

    /**
     * Store a draft for human review. Always unapproved, whatever confidence is claimed — a caller
     * cannot create a pre-approved answer, because approval is a human act and this method is not
     * one.
     */
    @Transactional
    public Optional<EmployerAnswer> draft(UUID userId, UUID questionId, String answerText,
                                          AnswerConfidence confidence, String source) {
        if (!enabled || userId == null || questionId == null) return Optional.empty();
        if (answerText == null || answerText.isBlank()) return Optional.empty();

        Instant now = Instant.now();
        EmployerAnswer answer = answers.findByUserIdAndQuestionId(userId, questionId)
                .orElseGet(() -> EmployerAnswer.builder()
                        .userId(userId).questionId(questionId)
                        .usageCount(0).createdAt(now).build());

        // Re-drafting an already-approved answer revokes approval: the text changed, so the human
        // decision that vouched for the previous text no longer applies to this one.
        answer.setAnswerText(answerText);
        answer.setConfidence((confidence == null ? AnswerConfidence.AI_SUGGESTED : confidence).name());
        answer.setApproved(false);
        answer.setApprovedBy(null);
        answer.setApprovedAt(null);
        answer.setSource(source == null ? "draft" : source);
        answer.setUpdatedAt(now);
        if (answer.getCreatedAt() == null) answer.setCreatedAt(now);

        return Optional.of(answers.save(answer));
    }

    /**
     * Approve a draft. The approving human's id and the moment are recorded; the confidence band is
     * promoted to {@link AnswerConfidence#HUMAN_APPROVED} unless it already carries a stronger
     * verified provenance, since "a human read it" is exactly what happened.
     */
    @Transactional
    public Optional<EmployerAnswer> approve(UUID userId, UUID answerId, UUID approvedBy) {
        if (!enabled || userId == null || answerId == null) return Optional.empty();

        Optional<EmployerAnswer> found = answers.findById(answerId);
        if (found.isEmpty()) return Optional.empty();
        EmployerAnswer answer = found.get();

        // Manual multi-tenant isolation, this codebase's convention: another user's answer is
        // indistinguishable from a non-existent one.
        if (!userId.equals(answer.getUserId())) return Optional.empty();
        if (answer.getAnswerText() == null || answer.getAnswerText().isBlank()) return Optional.empty();

        AnswerConfidence current = AnswerConfidence.parseOrUnknown(answer.getConfidence());
        if (!current.isUsableByAutomation() || current == AnswerConfidence.UNKNOWN) {
            answer.setConfidence(AnswerConfidence.HUMAN_APPROVED.name());
        }
        answer.setApproved(true);
        answer.setApprovedBy(approvedBy);
        answer.setApprovedAt(Instant.now());
        answer.setUpdatedAt(Instant.now());

        log.info("EMPLOYER_ANSWER approved answerId={} user={} confidence={}",
                answerId, userId, answer.getConfidence());
        return Optional.of(answers.save(answer));
    }

    /** Everything awaiting review, for the human review surface. */
    public List<EmployerAnswer> pendingReview(UUID userId) {
        if (!enabled || userId == null) return List.of();
        try {
            return answers.findByUserIdAndApproved(userId, false);
        } catch (Exception e) {
            log.warn("EMPLOYER_ANSWER pending read failed user={}: {}", userId, e.toString());
            return List.of();
        }
    }
}
