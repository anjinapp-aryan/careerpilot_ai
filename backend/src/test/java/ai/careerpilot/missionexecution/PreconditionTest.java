package ai.careerpilot.missionexecution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreconditionTest {

    @Test
    void gteIsMetWhenValueMeetsOrExceedsThreshold() {
        Precondition p = new Precondition("Resume Score >= 90", "resume.score", PreconditionOperator.GTE, 90);

        assertThat(p.isMet(Map.of("resume.score", 90.0))).isTrue();
        assertThat(p.isMet(Map.of("resume.score", 95.0))).isTrue();
        assertThat(p.isMet(Map.of("resume.score", 89.9))).isFalse();
    }

    @Test
    void isTrueTreatsNonZeroAsMet() {
        Precondition p = new Precondition("LinkedIn Complete", "linkedin.complete", PreconditionOperator.IS_TRUE, 1);

        assertThat(p.isMet(Map.of("linkedin.complete", 1.0))).isTrue();
        assertThat(p.isMet(Map.of("linkedin.complete", 0.0))).isFalse();
    }

    @Test
    void missingMetricIsTreatedAsNotMet() {
        Precondition p = new Precondition("Resume Score >= 90", "resume.score", PreconditionOperator.GTE, 90);

        assertThat(p.isMet(Map.of())).isFalse();
    }

    @Test
    void ltGtLteEqOperatorsAllWorkAsExpected() {
        assertThat(new Precondition("d", "k", PreconditionOperator.LT, 10).isMet(Map.of("k", 5.0))).isTrue();
        assertThat(new Precondition("d", "k", PreconditionOperator.LTE, 10).isMet(Map.of("k", 10.0))).isTrue();
        assertThat(new Precondition("d", "k", PreconditionOperator.GT, 10).isMet(Map.of("k", 11.0))).isTrue();
        assertThat(new Precondition("d", "k", PreconditionOperator.EQ, 10).isMet(Map.of("k", 10.0))).isTrue();
    }
}
