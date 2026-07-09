package ai.careerpilot.discovery.relevance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Phase 3B.1.1 — soft match-strength band boundaries: 90/80/70/60. */
class CareerMatchStrengthTest {

    @Test
    void bandsMatchSpecBoundaries() {
        assertEquals(CareerMatchStrength.EXCELLENT, CareerMatchStrength.fromScore(100));
        assertEquals(CareerMatchStrength.EXCELLENT, CareerMatchStrength.fromScore(90));
        assertEquals(CareerMatchStrength.STRONG, CareerMatchStrength.fromScore(89));
        assertEquals(CareerMatchStrength.STRONG, CareerMatchStrength.fromScore(80));
        assertEquals(CareerMatchStrength.GOOD, CareerMatchStrength.fromScore(79));
        assertEquals(CareerMatchStrength.GOOD, CareerMatchStrength.fromScore(70));
        assertEquals(CareerMatchStrength.WEAK, CareerMatchStrength.fromScore(69));
        assertEquals(CareerMatchStrength.WEAK, CareerMatchStrength.fromScore(60));
        assertEquals(CareerMatchStrength.HIDDEN, CareerMatchStrength.fromScore(59));
        assertEquals(CareerMatchStrength.HIDDEN, CareerMatchStrength.fromScore(0));
    }
}
