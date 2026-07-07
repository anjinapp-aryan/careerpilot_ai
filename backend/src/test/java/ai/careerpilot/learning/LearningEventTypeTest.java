package ai.careerpilot.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LearningEventTypeTest {

    @Test
    void has14ValuesPerSpec() {
        assertEquals(14, LearningEventType.values().length);
    }

    @Test
    void containsAllRequiredTypes() {
        var names = java.util.Arrays.stream(LearningEventType.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
        assertTrue(names.containsAll(java.util.List.of(
                "APPLICATION_SUBMITTED", "APPLICATION_REJECTED", "APPLICATION_ACCEPTED",
                "INTERVIEW_SCHEDULED", "INTERVIEW_COMPLETED",
                "OFFER_RECEIVED", "OFFER_ACCEPTED", "OFFER_REJECTED",
                "RESUME_SELECTED", "RESUME_REJECTED",
                "RECOMMENDATION_APPROVED", "RECOMMENDATION_REJECTED",
                "WORKFLOW_COMPLETED", "WORKFLOW_FAILED")));
    }
}
