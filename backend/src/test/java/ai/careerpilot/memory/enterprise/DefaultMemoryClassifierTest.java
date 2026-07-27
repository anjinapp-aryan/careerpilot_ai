package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryClassifierTest {

    private final DefaultMemoryClassifier classifier = new DefaultMemoryClassifier();

    @Test
    void classifiesDecisionLanguage() {
        assertThat(classifier.classify("I decided to accept the offer")).isEqualTo(MemoryType.DECISION);
    }

    @Test
    void classifiesPreferenceLanguage() {
        assertThat(classifier.classify("I prefer remote roles")).isEqualTo(MemoryType.PREFERENCE);
    }

    @Test
    void classifiesLearningLanguage() {
        assertThat(classifier.classify("I finished a course on Kubernetes")).isEqualTo(MemoryType.LEARNING);
    }

    @Test
    void classifiesSkillLanguage() {
        assertThat(classifier.classify("I'm proficient in Java")).isEqualTo(MemoryType.SKILL);
    }

    @Test
    void classifiesCareerLanguage() {
        assertThat(classifier.classify("My career goal is to become a staff engineer")).isEqualTo(MemoryType.CAREER);
    }

    @Test
    void classifiesConversationLanguage() {
        assertThat(classifier.classify("The recruiter mentioned a signing bonus")).isEqualTo(MemoryType.CONVERSATION);
    }

    @Test
    void unclassifiedContentDefaultsToWorking() {
        assertThat(classifier.classify("just some random note")).isEqualTo(MemoryType.WORKING);
    }

    @Test
    void blankOrNullContentDefaultsToWorking() {
        assertThat(classifier.classify("")).isEqualTo(MemoryType.WORKING);
        assertThat(classifier.classify(null)).isEqualTo(MemoryType.WORKING);
    }

    @Test
    void importanceScoreIncreasesWithUrgencyKeywords() {
        MemoryImportance plain = classifier.scoreImportance("some note", MemoryType.WORKING);
        MemoryImportance urgent = classifier.scoreImportance("this is critical and urgent", MemoryType.WORKING);

        assertThat(urgent.score()).isGreaterThan(plain.score());
    }

    @Test
    void nonWorkingTypeScoresSlightlyHigherThanWorking() {
        MemoryImportance working = classifier.scoreImportance("some note", MemoryType.WORKING);
        MemoryImportance career = classifier.scoreImportance("some note", MemoryType.CAREER);

        assertThat(career.score()).isGreaterThan(working.score());
    }
}
