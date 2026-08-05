package ai.careerpilot.employerquestion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase D — turns the many ways an employer can phrase one question into a single normalised form.
 *
 * <p>Pure, stateless, deterministic and thread-safe — the same discipline as
 * {@code VerificationAdjudicator} and {@code FormControlReducer}. No LLM: normalisation must give
 * the identical result on every run, because the normalised text is a database unique key. A
 * probabilistic normaliser would create duplicate "logical" questions on a retry, which is the
 * exact duplication this class exists to prevent.
 *
 * <p><b>What is stripped and why.</b> Politeness and framing carry no meaning ("please", "kindly",
 * "could you tell us"), nor do the pronouns and articles that differ between "your current country"
 * and "the country you currently live in". What survives is the content vocabulary — country,
 * live, current — which is what two phrasings of one question genuinely share.
 *
 * <p><b>What is deliberately NOT stripped:</b> negations. "Are you authorised to work here?" and
 * "Are you <em>not</em> authorised to work here?" are opposite questions, and normalising them
 * together would let an approved "yes" answer a question that meant the reverse.
 */
public final class QuestionNormalizer {

    private QuestionNormalizer() {
    }

    /**
     * Words carrying no distinguishing meaning. Negations ("not", "never", "without") are
     * intentionally absent — see the class note.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "am",
            "do", "does", "did", "have", "has", "had", "will", "would", "shall", "should",
            "can", "could", "may", "might", "must",
            "you", "your", "yours", "we", "us", "our", "i", "me", "my", "they", "them",
            "this", "that", "these", "those", "it", "its",
            "of", "to", "in", "on", "at", "for", "with", "by", "from", "as", "into",
            "and", "or", "if", "then", "than", "so",
            "please", "kindly", "tell", "let", "know", "provide", "enter", "specify",
            "what", "which", "who", "whom", "whose", "how", "when", "where", "why",
            "any", "some", "all", "there", "here", "about", "also", "just", "field",
            "question", "answer", "applicant", "candidate", "application");

    /**
     * The canonical key for a question. Lowercased, punctuation removed, stop words dropped, tokens
     * de-duplicated and sorted.
     *
     * <p>Sorting is what makes word order irrelevant, so "country of residence" and "residence
     * country" normalise identically. De-duplication stops a question that repeats a word from
     * looking different to one that does not.
     *
     * @return the normalised key, or an empty string when nothing meaningful survives — a caller
     *         must treat that as "not a question" rather than storing it, since an empty key would
     *         collide with every other meaningless question
     */
    public static String normalize(String text) {
        return String.join(" ", new java.util.TreeSet<>(tokens(text)));
    }

    /** Content tokens, in their original order, de-duplicated. */
    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        String cleaned = text.toLowerCase(Locale.ROOT)
                // Keep letters, digits and spaces. Apostrophes vanish so "company's" matches
                // "companys" -> "company" after the suffix strip below.
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        for (String raw : cleaned.split(" ")) {
            String token = stem(raw);
            if (token.length() < 2) continue;
            if (STOP_WORDS.contains(token)) continue;
            seen.add(token);
        }
        return new ArrayList<>(seen);
    }

    /**
     * A deliberately minimal suffix strip — plurals and the common verb endings that differ between
     * phrasings ("residing"/"reside", "restrictions"/"restriction"). Not a real stemmer: an
     * aggressive one conflates unrelated words, and a wrong conflation here silently reuses one
     * question's approved answer for a different question.
     */
    private static String stem(String word) {
        if (word.length() > 4 && word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
        if (word.length() > 4 && word.endsWith("ing")) return word.substring(0, word.length() - 3);
        if (word.length() > 3 && word.endsWith("es")) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s")) return word.substring(0, word.length() - 1);
        return word;
    }
}
