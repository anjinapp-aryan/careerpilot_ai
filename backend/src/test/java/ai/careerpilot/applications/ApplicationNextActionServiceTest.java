package ai.careerpilot.applications;

import ai.careerpilot.applications.ApplicationNextActionService.NextAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationNextActionServiceTest {

    private final ApplicationNextActionService svc = new ApplicationNextActionService();

    @Test
    void prefersLifecycleStatusWhenPresent() {
        NextAction a = svc.suggest("TECHNICAL_INTERVIEW", "INTERVIEWING");
        assertThat(a.action()).containsIgnoringCase("STAR stories");
        assertThat(a.suggestedAt()).isNotNull();
    }

    @Test
    void fallsBackToCoreStatusWhenLifecycleAbsent() {
        NextAction a = svc.suggest(null, "SAVED");
        assertThat(a.action()).containsIgnoringCase("apply");
        assertThat(a.suggestedAt()).isNotNull();
    }

    @Test
    void terminalStatusesHaveNoDueDate() {
        NextAction rejected = svc.suggest(null, "REJECTED");
        assertThat(rejected.suggestedAt()).isNull();

        NextAction withdrawn = svc.suggest("WITHDRAWN", "WITHDRAWN");
        assertThat(withdrawn.suggestedAt()).isNull();
    }

    @Test
    void offerReceivedSuggestsReviewingTerms() {
        NextAction a = svc.suggest("OFFER_RECEIVED", "OFFER");
        assertThat(a.action()).containsIgnoringCase("offer");
        assertThat(a.suggestedAt()).isNotNull();
    }

    @Test
    void unknownStatusFallsBackToGenericReview() {
        NextAction a = svc.suggest("SOME_UNKNOWN_STATUS", "SOME_UNKNOWN_STATUS");
        assertThat(a.action()).isEqualTo("Review this application's status");
    }

    @Test
    void appliedSuggestsWaitingWithFourteenDayFollowUp() {
        NextAction a = svc.suggest(null, "APPLIED");
        assertThat(a.action()).containsIgnoringCase("follow up");
    }
}
