package ai.careerpilot.workflow.tracking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static ai.careerpilot.domain.ApplicationLifecycle.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A.1 — exhaustive coverage of the pure lifecycle state machine: the happy forward path, the
 * always-reachable terminal transitions (reject/withdraw/expire from any active state), and the illegal
 * moves that must be refused (backwards, skipping, out of a terminal state, self, unknown, null).
 */
class ApplicationStatusMachineTest {

    @ParameterizedTest
    @CsvSource({
            "DRAFT,SUBMITTED",
            "SUBMITTED,VIEWED",
            "SUBMITTED,UNDER_REVIEW",
            "VIEWED,ASSESSMENT",
            "UNDER_REVIEW,TECHNICAL_INTERVIEW",
            "ASSESSMENT,TECHNICAL_INTERVIEW",
            "TECHNICAL_INTERVIEW,SYSTEM_DESIGN",
            "TECHNICAL_INTERVIEW,FINAL_ROUND",
            "SYSTEM_DESIGN,MANAGER_INTERVIEW",
            "MANAGER_INTERVIEW,HR_INTERVIEW",
            "HR_INTERVIEW,OFFER_RECEIVED",
            "FINAL_ROUND,OFFER_RECEIVED",
            "OFFER_RECEIVED,NEGOTIATION",
            "OFFER_RECEIVED,ACCEPTED",
            "NEGOTIATION,ACCEPTED"
    })
    void allowsForwardTransitions(String from, String to) {
        assertThat(ApplicationStatusMachine.canTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "VIEWED", "UNDER_REVIEW", "ASSESSMENT", "TECHNICAL_INTERVIEW",
            "MANAGER_INTERVIEW", "SYSTEM_DESIGN", "HR_INTERVIEW", "FINAL_ROUND", "OFFER_RECEIVED", "NEGOTIATION"})
    void rejectWithdrawExpireReachableFromAnyActiveState(String from) {
        assertThat(ApplicationStatusMachine.canTransition(from, STATUS_REJECTED)).isTrue();
        assertThat(ApplicationStatusMachine.canTransition(from, STATUS_WITHDRAWN)).isTrue();
        assertThat(ApplicationStatusMachine.canTransition(from, STATUS_EXPIRED)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "SUBMITTED,DRAFT",             // backwards
            "VIEWED,SUBMITTED",           // backwards
            "SUBMITTED,OFFER_RECEIVED",   // skips the whole funnel
            "DRAFT,ACCEPTED",             // skips to terminal
            "ASSESSMENT,SYSTEM_DESIGN"    // not a legal forward edge
    })
    void refusesIllegalTransitions(String from, String to) {
        assertThat(ApplicationStatusMachine.canTransition(from, to)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "REJECTED", "WITHDRAWN", "EXPIRED"})
    void terminalStatesHaveNoExit(String terminal) {
        assertThat(ApplicationStatusMachine.isTerminal(terminal)).isTrue();
        assertThat(ApplicationStatusMachine.canTransition(terminal, STATUS_SUBMITTED)).isFalse();
        assertThat(ApplicationStatusMachine.canTransition(terminal, STATUS_WITHDRAWN)).isFalse();
    }

    @Test
    void refusesSelfNullAndUnknown() {
        assertThat(ApplicationStatusMachine.canTransition(STATUS_SUBMITTED, STATUS_SUBMITTED)).isFalse();
        assertThat(ApplicationStatusMachine.canTransition(null, STATUS_SUBMITTED)).isFalse();
        assertThat(ApplicationStatusMachine.canTransition(STATUS_SUBMITTED, null)).isFalse();
        assertThat(ApplicationStatusMachine.canTransition("NOPE", STATUS_SUBMITTED)).isFalse();
        assertThat(ApplicationStatusMachine.canTransition(STATUS_SUBMITTED, "NOPE")).isFalse();
    }

    @Test
    void knownStatusesRecognised() {
        assertThat(ApplicationStatusMachine.isKnown(STATUS_DRAFT)).isTrue();
        assertThat(ApplicationStatusMachine.isKnown(STATUS_ACCEPTED)).isTrue();
        assertThat(ApplicationStatusMachine.isKnown("NOPE")).isFalse();
    }
}
