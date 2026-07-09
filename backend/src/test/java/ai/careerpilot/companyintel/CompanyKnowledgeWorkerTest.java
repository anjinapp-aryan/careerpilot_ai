package ai.careerpilot.companyintel;

import ai.careerpilot.learning.LearningEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Learning-outcome → company-timeline mapping: every enum value maps (or is deliberately generic). */
class CompanyKnowledgeWorkerTest {

    @Test
    void everyLearningEventTypeMapsWithoutThrowing() {
        for (LearningEventType type : LearningEventType.values()) {
            assertNotNull(CompanyKnowledgeWorker.mapEventType(type), type + " must map to a milestone");
        }
        assertNull(CompanyKnowledgeWorker.mapEventType(null));
    }

    @Test
    void outcomeSemanticsArePreserved() {
        assertEquals(CompanyTimelineEventType.APPLICATION_SUBMITTED,
                CompanyKnowledgeWorker.mapEventType(LearningEventType.APPLICATION_SUBMITTED));
        assertEquals(CompanyTimelineEventType.OFFER_RECEIVED,
                CompanyKnowledgeWorker.mapEventType(LearningEventType.OFFER_RECEIVED));
        assertEquals(CompanyTimelineEventType.REJECTED,
                CompanyKnowledgeWorker.mapEventType(LearningEventType.APPLICATION_REJECTED));
        assertEquals(CompanyTimelineEventType.INTERVIEW_COMPLETED,
                CompanyKnowledgeWorker.mapEventType(LearningEventType.INTERVIEW_COMPLETED));
        assertEquals(CompanyTimelineEventType.RECOMMENDED,
                CompanyKnowledgeWorker.mapEventType(LearningEventType.RECOMMENDATION_APPROVED));
    }
}
