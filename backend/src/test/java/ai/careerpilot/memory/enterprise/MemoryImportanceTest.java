package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryImportanceTest {

    @Test
    void bandsScoreIntoLevels() {
        assertThat(new MemoryImportance(0.9).level()).isEqualTo(MemoryImportance.Level.CRITICAL);
        assertThat(new MemoryImportance(0.85).level()).isEqualTo(MemoryImportance.Level.CRITICAL);
        assertThat(new MemoryImportance(0.7).level()).isEqualTo(MemoryImportance.Level.HIGH);
        assertThat(new MemoryImportance(0.4).level()).isEqualTo(MemoryImportance.Level.MEDIUM);
        assertThat(new MemoryImportance(0.1).level()).isEqualTo(MemoryImportance.Level.LOW);
    }

    @Test
    void clampsOutOfRangeScores() {
        assertThat(new MemoryImportance(2.0).score()).isEqualTo(1.0);
        assertThat(new MemoryImportance(-1.0).score()).isEqualTo(0.0);
    }
}
