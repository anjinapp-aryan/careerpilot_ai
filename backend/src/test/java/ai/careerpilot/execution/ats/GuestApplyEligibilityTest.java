package ai.careerpilot.execution.ats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap D — the hardcoded, non-configurable guest-apply allowlist. This is the actual enforcement
 * point (not any feature flag) that a login-required connector such as LinkedIn can never be
 * routed onto the real browser automation path.
 */
class GuestApplyEligibilityTest {

    @Test
    void greenhouseAndLeverAreEligible() {
        assertThat(GuestApplyEligibility.isEligible("greenhouse")).isTrue();
        assertThat(GuestApplyEligibility.isEligible("lever")).isTrue();
    }

    @Test
    void caseInsensitive() {
        assertThat(GuestApplyEligibility.isEligible("GreenHouse")).isTrue();
        assertThat(GuestApplyEligibility.isEligible("LEVER")).isTrue();
    }

    @Test
    void linkedInIsNeverEligible_requiresLogin() {
        assertThat(GuestApplyEligibility.isEligible("linkedin")).isFalse();
    }

    @Test
    void loginRequiredConnectorsAreNotEligible() {
        assertThat(GuestApplyEligibility.isEligible("workday")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("smartrecruiters")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("ashby")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("bamboohr")).isFalse();
    }

    @Test
    void nullOrBlankIsNotEligible() {
        assertThat(GuestApplyEligibility.isEligible(null)).isFalse();
        assertThat(GuestApplyEligibility.isEligible("")).isFalse();
        assertThat(GuestApplyEligibility.isEligible("   ")).isFalse();
    }

    @Test
    void unknownConnectorIsNotEligible() {
        assertThat(GuestApplyEligibility.isEligible("some-random-ats")).isFalse();
    }
}
