package ai.careerpilot.submission.question;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QuestionDetectionServiceTest {

    private final QuestionDetectionService service = new QuestionDetectionService();

    @Test
    void classifiesSalaryQuestions() {
        assertEquals(QuestionCategory.SALARY, service.classify("What are your salary expectations?"));
        assertEquals(QuestionCategory.SALARY, service.classify("Please share your expected CTC"));
    }

    @Test
    void classifiesNoticePeriodQuestions() {
        assertEquals(QuestionCategory.NOTICE_PERIOD, service.classify("What is your notice period?"));
        assertEquals(QuestionCategory.NOTICE_PERIOD, service.classify("When can you start?"));
    }

    @Test
    void classifiesVisaQuestions() {
        assertEquals(QuestionCategory.VISA, service.classify("Do you require visa sponsorship?"));
        assertEquals(QuestionCategory.VISA, service.classify("Are you authorized to work in this country?"));
    }

    @Test
    void classifiesRelocationQuestions() {
        assertEquals(QuestionCategory.RELOCATION, service.classify("Are you willing to relocate for this role?"));
        assertEquals(QuestionCategory.RELOCATION, service.classify("Are you willing to move to a new city?"));
    }

    @Test
    void classifiesRemotePreferenceQuestions() {
        assertEquals(QuestionCategory.REMOTE_PREFERENCE, service.classify("What is your remote-work preference?"));
        assertEquals(QuestionCategory.REMOTE_PREFERENCE, service.classify("Do you prefer hybrid or onsite?"));
    }

    @Test
    void classifiesLeadershipQuestions() {
        assertEquals(QuestionCategory.LEADERSHIP, service.classify("Describe a time you led a team or project."));
        assertEquals(QuestionCategory.LEADERSHIP, service.classify("Tell us about managing a team."));
    }

    @Test
    void classifiesConflictQuestions() {
        assertEquals(QuestionCategory.CONFLICT, service.classify("Describe a conflict you resolved at work."));
        assertEquals(QuestionCategory.CONFLICT, service.classify("Tell us about a disagreement with a colleague."));
    }

    @Test
    void classifiesFailureQuestions() {
        assertEquals(QuestionCategory.FAILURE, service.classify("Tell us about a time you failed and what you learned."));
        assertEquals(QuestionCategory.FAILURE, service.classify("Describe a project that went wrong."));
    }

    @Test
    void classifiesAchievementQuestions() {
        assertEquals(QuestionCategory.ACHIEVEMENT, service.classify("What is your proudest professional achievement?"));
        assertEquals(QuestionCategory.ACHIEVEMENT, service.classify("Describe your greatest success."));
    }

    @Test
    void classifiesWhyRoleQuestions() {
        assertEquals(QuestionCategory.WHY_ROLE, service.classify("Why are you interested in this role?"));
        assertEquals(QuestionCategory.WHY_ROLE, service.classify("Why do you want to work here?"));
    }

    @Test
    void classifiesAboutYouQuestions() {
        assertEquals(QuestionCategory.ABOUT_YOU, service.classify("Tell us about yourself."));
        assertEquals(QuestionCategory.ABOUT_YOU, service.classify("Please introduce yourself."));
    }

    @Test
    void unmatchedTextClassifiesAsOther() {
        assertEquals(QuestionCategory.OTHER, service.classify("What is the capital of France?"));
    }

    @Test
    void nullOrBlankClassifiesAsOther() {
        assertEquals(QuestionCategory.OTHER, service.classify(null));
        assertEquals(QuestionCategory.OTHER, service.classify(""));
        assertEquals(QuestionCategory.OTHER, service.classify("   "));
    }

    @Test
    void classificationIsCaseInsensitive() {
        assertEquals(QuestionCategory.SALARY, service.classify("WHAT IS YOUR SALARY EXPECTATION?"));
    }

    @Test
    void commonQuestionsReturnsExactlyElevenEntries() {
        List<Map.Entry<String, QuestionCategory>> qs = service.commonQuestions();
        assertEquals(11, qs.size());
    }

    @Test
    void commonQuestionsCoverAllElevenNonOtherCategories() {
        List<Map.Entry<String, QuestionCategory>> qs = service.commonQuestions();
        Set<QuestionCategory> categories = qs.stream().map(Map.Entry::getValue).collect(java.util.stream.Collectors.toSet());
        for (QuestionCategory c : QuestionCategory.values()) {
            if (c == QuestionCategory.OTHER) continue;
            assertTrue(categories.contains(c), "missing common question for category " + c);
        }
        assertEquals(11, categories.size());
    }

    @Test
    void commonQuestionsHaveNoDuplicateQuestionText() {
        List<Map.Entry<String, QuestionCategory>> qs = service.commonQuestions();
        Set<String> texts = qs.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        assertEquals(qs.size(), texts.size());
    }
}
