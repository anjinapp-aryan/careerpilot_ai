package ai.careerpilot.workflow.email;

import ai.careerpilot.domain.ApplicationEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A.3 — the pure keyword classifier + extractor. Verifies the category matrix, the documented
 * precedence (OFFER &gt; REJECTION &gt; INTERVIEW &gt; ASSESSMENT &gt; ACKNOWLEDGEMENT &gt; WITHDRAWAL),
 * and best-effort salary/interview-type extraction. No mailbox, no LLM — deterministic.
 */
class EmailClassifierTest {

    @ParameterizedTest
    @CsvSource({
            "We are pleased to offer you the role,OFFER",
            "Unfortunately we will not be moving forward,REJECTION",
            "Schedule a technical interview,INTERVIEW",
            "Please complete this coding challenge,ASSESSMENT",
            "Thank you for applying; application received,ACKNOWLEDGEMENT",
            "Your application has been withdrawn,WITHDRAWAL",
            "Newsletter: this week at Acme,UNKNOWN"
    })
    void classifiesBySubject(String subject, String expected) {
        assertThat(EmailClassifier.classify(subject, "").category()).isEqualTo(expected);
    }

    @Test
    void offerBeatsInterviewWhenBothPresent() {
        // precedence: an offer email that also mentions the interview stage must classify as OFFER
        EmailClassifier.Classification c = EmailClassifier.classify(
                "Job offer after your interview", "We are pleased to offer you the position");
        assertThat(c.category()).isEqualTo(ApplicationEmail.CATEGORY_OFFER);
        assertThat(c.confidence()).isGreaterThan(0.0);
    }

    @Test
    void rejectionBeatsInterview() {
        EmailClassifier.Classification c = EmailClassifier.classify(
                "Update on your interview", "Unfortunately we decided not to proceed");
        assertThat(c.category()).isEqualTo(ApplicationEmail.CATEGORY_REJECTION);
    }

    @Test
    void unknownHasZeroConfidence() {
        assertThat(EmailClassifier.classify("hello", "nothing relevant").confidence()).isEqualTo(0.0);
    }

    @Test
    void nullSubjectAndBodyAreSafe() {
        assertThat(EmailClassifier.classify(null, null).category()).isEqualTo(ApplicationEmail.CATEGORY_UNKNOWN);
    }

    @Test
    void extractsInterviewType() {
        assertThat(EmailClassifier.extract("System Design round", "").interviewType()).isEqualTo("SYSTEM_DESIGN");
        assertThat(EmailClassifier.extract("Technical screen", "").interviewType()).isEqualTo("TECHNICAL");
        assertThat(EmailClassifier.extract("Final round", "").interviewType()).isEqualTo("FINAL");
        assertThat(EmailClassifier.extract("Just a chat", "").interviewType()).isNull();
    }

    @Test
    void extractsSalaryToken() {
        assertThat(EmailClassifier.extract("Offer", "The base is $120,000 per year").salary()).contains("120,000");
        assertThat(EmailClassifier.extract("Offer", "CTC of 18 LPA").salary()).containsIgnoringCase("LPA");
        assertThat(EmailClassifier.extract("Offer", "no numbers here").salary()).isNull();
    }
}
