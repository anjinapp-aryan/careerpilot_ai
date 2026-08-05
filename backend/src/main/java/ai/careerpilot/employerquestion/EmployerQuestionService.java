package ai.careerpilot.employerquestion;

import ai.careerpilot.domain.EmployerQuestion;
import ai.careerpilot.repo.EmployerQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase D — the employer question library: records what employers ask, deduplicated into one row
 * per logical question.
 *
 * <p>Recording a question is <b>observation only</b>. It never creates an answer, never drafts one,
 * and never touches automation — the phase's rule that answers are not auto-created during
 * submission is enforced here by this service simply having no code that could.
 *
 * <p>Gated by {@code employer.question.intelligence.enabled} (default {@code false}), this
 * codebase's dark-by-default convention. With the flag off every method is a no-op returning empty,
 * so nothing observes, stores, or reuses anything.
 */
@Service
public class EmployerQuestionService {

    private static final Logger log = LoggerFactory.getLogger(EmployerQuestionService.class);

    private final EmployerQuestionRepository questions;
    private final boolean enabled;

    public EmployerQuestionService(EmployerQuestionRepository questions,
                                   @Value("${employer.question.intelligence.enabled:false}") boolean enabled) {
        this.questions = questions;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** What an observed question looks like before it is stored. */
    public record Observation(String originalText, String canonicalField, String questionCategory,
                              String questionType, boolean required, String employer, String atsPlatform) {}

    /**
     * Record a sighting, deduplicating onto an existing logical question when one matches.
     *
     * <p>Matching runs the full two-tier engine rather than an exact lookup, so a rephrasing by a
     * different employer increments the existing row instead of creating a near-duplicate. Only the
     * sighting counters and last-seen provenance are updated on a repeat — the canonical mapping of
     * an established question is never rewritten by a later, differently-worded sighting.
     */
    @Transactional
    public Optional<EmployerQuestion> record(Observation observation) {
        if (!enabled || observation == null) return Optional.empty();
        String normalized = QuestionNormalizer.normalize(observation.originalText());
        if (normalized.isEmpty()) {
            // Nothing meaningful survived normalisation. Storing it would produce a row whose key
            // collides with every other meaningless question.
            return Optional.empty();
        }

        try {
            Optional<EmployerQuestion> existing = questions.findByNormalizedText(normalized)
                    .or(() -> QuestionMatchingEngine
                            .match(observation.originalText(), observation.canonicalField(), library())
                            .map(QuestionMatchingEngine.Match::question));

            if (existing.isPresent()) {
                EmployerQuestion q = existing.get();
                q.setTimesSeen(q.getTimesSeen() + 1);
                q.setLastSeenAt(Instant.now());
                if (observation.employer() != null) q.setEmployer(observation.employer());
                if (observation.atsPlatform() != null) q.setAtsPlatform(observation.atsPlatform());
                return Optional.of(questions.save(q));
            }

            Instant now = Instant.now();
            EmployerQuestion created = questions.save(EmployerQuestion.builder()
                    .originalText(observation.originalText())
                    .normalizedText(normalized)
                    .canonicalField(observation.canonicalField() == null ? "UNKNOWN" : observation.canonicalField())
                    .questionCategory(observation.questionCategory() == null ? "OTHER" : observation.questionCategory())
                    .questionType(observation.questionType() == null ? "TEXT" : observation.questionType())
                    .required(observation.required())
                    .confidence(0)
                    .employer(observation.employer())
                    .atsPlatform(observation.atsPlatform())
                    .timesSeen(1)
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .build());
            log.info("EMPLOYER_QUESTION recorded id={} category={} canonical={}",
                    created.getId(), created.getQuestionCategory(), created.getCanonicalField());
            return Optional.of(created);
        } catch (Exception e) {
            // Observation must never break a caller: a library that fails to record a question is
            // less bad than one that fails the operation that discovered it.
            log.warn("EMPLOYER_QUESTION record failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Resolve a question text onto an already-known logical question. */
    public Optional<QuestionMatchingEngine.Match> find(String questionText, String canonicalField) {
        if (!enabled) return Optional.empty();
        return QuestionMatchingEngine.match(questionText, canonicalField, library());
    }

    /** Bounded read; never an unbounded table scan on a request path. */
    public List<EmployerQuestion> library() {
        if (!enabled) return List.of();
        try {
            return questions.findTop200ByOrderByLastSeenAtDesc();
        } catch (Exception e) {
            log.warn("EMPLOYER_QUESTION library read failed: {}", e.toString());
            return List.of();
        }
    }
}
