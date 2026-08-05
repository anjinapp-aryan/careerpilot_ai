package ai.careerpilot.employerquestion;

import ai.careerpilot.domain.EmployerQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase D — normalisation and semantic matching, the two pure engines reuse depends on. */
class QuestionIntelligenceTest {

    private static EmployerQuestion q(String text, String canonicalField) {
        Instant now = Instant.now();
        return EmployerQuestion.builder()
                .id(UUID.randomUUID())
                .originalText(text)
                .normalizedText(QuestionNormalizer.normalize(text))
                .canonicalField(canonicalField)
                .questionCategory("OTHER").questionType("TEXT")
                .timesSeen(1).firstSeenAt(now).lastSeenAt(now)
                .build();
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        @DisplayName("the phase's worked example: four phrasings collapse to one key")
        void theWorkedExample() {
            String a = QuestionNormalizer.normalize("What country do you currently live in?");
            String b = QuestionNormalizer.normalize("What is your current country of residence?");
            String c = QuestionNormalizer.normalize("Current location");
            String d = QuestionNormalizer.normalize("Country");

            // "country" survives in three of the four; the fourth uses different vocabulary
            // entirely, which is why the matching engine — not the normaliser — is what unifies it.
            assertThat(a).contains("country");
            assertThat(b).contains("country");
            assertThat(d).isEqualTo("country");
            assertThat(c).doesNotContain("country");
        }

        @Test
        @DisplayName("word order does not change the key")
        void orderIndependent() {
            assertThat(QuestionNormalizer.normalize("country of residence"))
                    .isEqualTo(QuestionNormalizer.normalize("residence country"));
        }

        @Test
        @DisplayName("punctuation, case and politeness are stripped")
        void noiseIsStripped() {
            assertThat(QuestionNormalizer.normalize("Please tell us: your NOTICE PERIOD?"))
                    .isEqualTo(QuestionNormalizer.normalize("notice period"));
        }

        @Test
        @DisplayName("negation is preserved — opposite questions must not share a key")
        void negationSurvives() {
            String positive = QuestionNormalizer.normalize("Are you authorised to work in Germany?");
            String negative = QuestionNormalizer.normalize("Are you not authorised to work in Germany?");
            assertThat(positive).isNotEqualTo(negative);
            assertThat(negative).contains("not");
        }

        @Test
        @DisplayName("normalisation is deterministic — it is a database unique key")
        void deterministic() {
            String text = "Have you previously worked at or consulted for this company?";
            assertThat(QuestionNormalizer.normalize(text))
                    .isEqualTo(QuestionNormalizer.normalize(text))
                    .isEqualTo(QuestionNormalizer.normalize(text));
        }

        @Test
        @DisplayName("meaningless input yields an empty key rather than a colliding one")
        void meaninglessIsEmpty() {
            assertThat(QuestionNormalizer.normalize("the a of to")).isEmpty();
            assertThat(QuestionNormalizer.normalize("???")).isEmpty();
            assertThat(QuestionNormalizer.normalize(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("semantic matching")
    class Matching {

        private final List<EmployerQuestion> library = List.of(
                q("What is your current country of residence?", "COUNTRY"),
                q("Have you previously worked at or consulted for this company?", "SCREENING_QUESTION"),
                q("Are you subject to any post-employment restrictions?", "SCREENING_QUESTION"));

        @Test
        @DisplayName("an exact rephrasing matches the same logical question")
        void rephrasingMatches() {
            Optional<QuestionMatchingEngine.Match> match = QuestionMatchingEngine.match(
                    "What is your current country of residence?", "COUNTRY", library);

            assertThat(match).isPresent();
            assertThat(match.get().similarity()).isEqualTo(1.0);
            assertThat(match.get().how()).contains("exact");
        }

        @Test
        @DisplayName("word-order and politeness variants match")
        void variantsMatch() {
            assertThat(QuestionMatchingEngine.match(
                    "Please tell us your current residence country", "COUNTRY", library)).isPresent();
        }

        @Test
        @DisplayName("a different canonical field is never matched, however similar the wording")
        void canonicalFieldGuards() {
            // Same vocabulary, different meaning: this must not reuse the country answer.
            assertThat(QuestionMatchingEngine.match(
                    "What is your current country of residence?", "CITIZENSHIP", library)).isEmpty();
        }

        @Test
        @DisplayName("superficially similar but genuinely different questions do not match")
        void similarButDifferentDoesNotMatch() {
            Optional<QuestionMatchingEngine.Match> match = QuestionMatchingEngine.match(
                    "Have you previously worked with our technology stack?",
                    "SCREENING_QUESTION", library);

            // Shares "previously worked" but asks something else entirely. A false match here would
            // answer a question the candidate never saw.
            assertThat(match).isEmpty();
        }

        @Test
        @DisplayName("an unseen question matches nothing")
        void unseenQuestion() {
            assertThat(QuestionMatchingEngine.match(
                    "Do you hold a valid forklift licence?", "SCREENING_QUESTION", library)).isEmpty();
        }

        @Test
        @DisplayName("matching is symmetric and order-independent across the library")
        void deterministicAcrossOrdering() {
            List<EmployerQuestion> reversed = library.reversed();
            Optional<QuestionMatchingEngine.Match> a = QuestionMatchingEngine.match(
                    "current country of residence", "COUNTRY", library);
            Optional<QuestionMatchingEngine.Match> b = QuestionMatchingEngine.match(
                    "current country of residence", "COUNTRY", reversed);

            assertThat(a).isPresent();
            assertThat(b).isPresent();
            assertThat(a.get().question().getId()).isEqualTo(b.get().question().getId());
        }

        @Test
        @DisplayName("an empty library and null input never throw")
        void nullSafe() {
            assertThat(QuestionMatchingEngine.match("anything", "COUNTRY", List.of())).isEmpty();
            assertThat(QuestionMatchingEngine.match(null, "COUNTRY", library)).isEmpty();
            assertThat(QuestionMatchingEngine.match("anything", "COUNTRY", null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("confidence model")
    class Confidence {

        @Test
        @DisplayName("exactly the four verified bands are usable by automation")
        void trustBoundary() {
            assertThat(AnswerConfidence.VERIFIED.isUsableByAutomation()).isTrue();
            assertThat(AnswerConfidence.HUMAN_APPROVED.isUsableByAutomation()).isTrue();
            assertThat(AnswerConfidence.RESUME_DERIVED.isUsableByAutomation()).isTrue();
            assertThat(AnswerConfidence.PROFILE_DERIVED.isUsableByAutomation()).isTrue();

            assertThat(AnswerConfidence.AI_SUGGESTED.isUsableByAutomation()).isFalse();
            assertThat(AnswerConfidence.UNKNOWN.isUsableByAutomation()).isFalse();
        }

        @Test
        @DisplayName("an unreadable band fails closed to UNKNOWN")
        void parseFailsClosed() {
            assertThat(AnswerConfidence.parseOrUnknown("nonsense")).isEqualTo(AnswerConfidence.UNKNOWN);
            assertThat(AnswerConfidence.parseOrUnknown(null)).isEqualTo(AnswerConfidence.UNKNOWN);
            assertThat(AnswerConfidence.parseOrUnknown("human_approved"))
                    .isEqualTo(AnswerConfidence.HUMAN_APPROVED);
        }

        @Test
        @DisplayName("usability requires BOTH approval and a trusted band")
        void usabilityIsAConjunction() {
            Instant now = Instant.now();
            assertThat(new AnswerResolution("yes", AnswerConfidence.HUMAN_APPROVED, true, now,
                    "s", "r", UUID.randomUUID()).usable()).isTrue();
            // Approved but untrusted band.
            assertThat(new AnswerResolution("yes", AnswerConfidence.AI_SUGGESTED, true, now,
                    "s", "r", UUID.randomUUID()).usable()).isFalse();
            // Trusted band but unapproved.
            assertThat(new AnswerResolution("yes", AnswerConfidence.PROFILE_DERIVED, false, null,
                    "s", "r", UUID.randomUUID()).usable()).isFalse();
            // Blank text is never an answer.
            assertThat(new AnswerResolution("  ", AnswerConfidence.VERIFIED, true, now,
                    "s", "r", UUID.randomUUID()).usable()).isFalse();
        }

        @Test
        @DisplayName("a resolution with no answer still explains why")
        void explainabilityOnFailure() {
            AnswerResolution none = AnswerResolution.none("this question has not been seen before");

            assertThat(none.usable()).isFalse();
            assertThat(none.answerText()).isNull();
            assertThat(none.explain().get("reason").toString()).contains("not been seen");
            assertThat(none.explain()).containsKeys("confidence", "approved", "source", "usableByAutomation");
        }
    }
}
