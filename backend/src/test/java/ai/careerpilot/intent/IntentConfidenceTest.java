package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentConfidenceTest {

    @Test
    void bandsScoreIntoLevels() {
        assertThat(new IntentConfidence(0.9).level()).isEqualTo(IntentConfidence.Level.HIGH);
        assertThat(new IntentConfidence(0.66).level()).isEqualTo(IntentConfidence.Level.HIGH);
        assertThat(new IntentConfidence(0.5).level()).isEqualTo(IntentConfidence.Level.MEDIUM);
        assertThat(new IntentConfidence(0.33).level()).isEqualTo(IntentConfidence.Level.MEDIUM);
        assertThat(new IntentConfidence(0.1).level()).isEqualTo(IntentConfidence.Level.LOW);
        assertThat(new IntentConfidence(0.0).level()).isEqualTo(IntentConfidence.Level.LOW);
    }

    @Test
    void clampsOutOfRangeScores() {
        assertThat(new IntentConfidence(1.5).score()).isEqualTo(1.0);
        assertThat(new IntentConfidence(-0.5).score()).isEqualTo(0.0);
    }

    @Test
    void zeroFactoryProducesLowConfidence() {
        assertThat(IntentConfidence.zero().level()).isEqualTo(IntentConfidence.Level.LOW);
    }
}
