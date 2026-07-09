package ai.careerpilot.workflow.career;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase 3A.6 — the pure, deterministic career-success math. Overall success is a weighted blend that
 * favours the harder-to-reach signal (a real offer, 0.6) over merely reaching interviews (0.4); every
 * output is clamped to [0,1].
 */
class CareerProbabilityTest {

    @ParameterizedTest
    @CsvSource({
            "0.0,0.0,0.0",
            "1.0,1.0,1.0",
            "1.0,0.0,0.4",   // interviews only -> interview weight
            "0.0,1.0,0.6",   // offers only -> offer weight
            "0.5,0.5,0.5"
    })
    void blendsInterviewAndOfferRates(double interview, double offer, double expected) {
        assertThat(CareerProbability.careerSuccess(interview, offer)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void clampsNegativeAndNaNToZero() {
        assertThat(CareerProbability.clamp(-1.0)).isEqualTo(0.0);
        assertThat(CareerProbability.clamp(Double.NaN)).isEqualTo(0.0);
    }

    @Test
    void clampsAboveOneToOne() {
        assertThat(CareerProbability.clamp(2.5)).isEqualTo(1.0);
    }

    @Test
    void outOfRangeInputsAreClampedBeforeBlending() {
        // negative interview treated as 0, over-unity offer treated as 1 -> 0.6
        assertThat(CareerProbability.careerSuccess(-5.0, 5.0)).isCloseTo(0.6, within(1e-9));
    }
}
