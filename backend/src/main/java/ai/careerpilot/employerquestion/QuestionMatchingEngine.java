package ai.careerpilot.employerquestion;

import ai.careerpilot.domain.EmployerQuestion;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase D — decides whether a newly-seen question is one the library already knows.
 *
 * <p>Pure, stateless, deterministic and thread-safe. No LLM and no embeddings: a match here causes
 * an already-approved answer to be reused without further review, so a non-reproducible matcher
 * would mean the same question sometimes reuses an answer and sometimes asks the human again — and,
 * worse, could occasionally attach one question's approved answer to a different question.
 *
 * <p>"Semantic" here means <b>vocabulary overlap after normalisation</b>, not string equality.
 * "What country do you currently live in?", "What is your current country of residence?" and
 * "Country" all reduce to overlapping content tokens and match; nothing about the employer, the ATS
 * or the surrounding form participates.
 *
 * <h2>Two tiers, and why the second is deliberately strict</h2>
 * <ol>
 *   <li><b>Exact normalised match</b> — certain, and the common case.</li>
 *   <li><b>Jaccard token similarity</b> at or above {@link #SIMILARITY_THRESHOLD}, and only within
 *       the same canonical field. The category guard is what stops "Have you previously worked
 *       here?" matching "Have you previously worked with our technology?" purely on shared
 *       vocabulary — a false match there silently answers a question the candidate never saw.</li>
 * </ol>
 * A short question is additionally required to share <em>every</em> one of its tokens, because with
 * two or three tokens Jaccard is too coarse to be trusted.
 */
public final class QuestionMatchingEngine {

    private QuestionMatchingEngine() {
    }

    /**
     * Tuned to be conservative. The cost of a missed match is one extra human review; the cost of a
     * wrong match is a wrong answer submitted to an employer, so the two errors are not remotely
     * symmetric and the threshold is set accordingly.
     */
    public static final double SIMILARITY_THRESHOLD = 0.8;

    /** Below this token count, similarity alone is not evidence; full containment is required. */
    private static final int SHORT_QUESTION_TOKENS = 4;

    /** One candidate match with the score that produced it, for explainability. */
    public record Match(EmployerQuestion question, double similarity, String how) {}

    /**
     * Find the library question this text refers to, if any.
     *
     * @param canonicalField the canonical field the classifier assigned; a candidate whose field
     *                       differs is never matched, whatever its wording
     */
    public static Optional<Match> match(String questionText, String canonicalField,
                                        List<EmployerQuestion> library) {
        if (questionText == null || library == null || library.isEmpty()) return Optional.empty();
        String normalized = QuestionNormalizer.normalize(questionText);
        if (normalized.isEmpty()) return Optional.empty();

        for (EmployerQuestion q : library) {
            // The canonical-field guard applies to the exact tier too. Identical wording that the
            // classifier now reads as a different field is a different question: reusing the stored
            // answer would answer a country question with a citizenship answer, which is exactly
            // the failure the guard exists to prevent. Identical text is strong evidence, not proof.
            if (differentField(canonicalField, q)) continue;
            if (normalized.equals(q.getNormalizedText())) {
                return Optional.of(new Match(q, 1.0, "exact normalised match"));
            }
        }

        Set<String> tokens = new HashSet<>(QuestionNormalizer.tokens(questionText));
        if (tokens.isEmpty()) return Optional.empty();

        Match best = null;
        for (EmployerQuestion q : library) {
            // Same-meaning guard. Two questions that map to different canonical fields are
            // different questions however similar they read.
            if (differentField(canonicalField, q)) continue;
            Set<String> other = new HashSet<>(List.of(q.getNormalizedText().split(" ")));
            if (other.isEmpty()) continue;

            double similarity = jaccard(tokens, other);
            if (similarity < SIMILARITY_THRESHOLD) continue;

            // A short question must be fully contained, not merely similar.
            if ((tokens.size() < SHORT_QUESTION_TOKENS || other.size() < SHORT_QUESTION_TOKENS)
                    && !other.containsAll(tokens) && !tokens.containsAll(other)) {
                continue;
            }
            if (best == null || similarity > best.similarity()) {
                best = new Match(q, similarity,
                        "token similarity " + Math.round(similarity * 100) + "% within " + canonicalField);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Whether a library question means something different from what the caller is asking about.
     * A null on either side is not evidence of difference, so it never blocks a match — an
     * unclassified question is unknown, not incompatible.
     */
    private static boolean differentField(String canonicalField, EmployerQuestion q) {
        return canonicalField != null && q.getCanonicalField() != null
                && !canonicalField.equals(q.getCanonicalField());
    }

    /** Intersection over union. Symmetric, so match order can never affect the result. */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
