package ai.careerpilot.jobdiscovery.priority;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobScoring;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2C-1 — pins the additive bonus math (Step 4's worked example: base 90 + all bonuses = 130)
 * and the priority bands, plus that each bonus fires only on its explicit signal.
 */
class PriorityEngineTest {

    private final PriorityEngine engine = new PriorityEngine(true);

    /** breakdown with tunable role/salary; other factors neutral. */
    private static JobScoring.ScoreResultV2 result(int score, int role, int salary) {
        return new JobScoring.ScoreResultV2(score, List.of(), List.of(),
                new JobScoring.ScoreBreakdown(50, 50, role, 50, salary, 50, 50), "HIGH", 3, 1);
    }

    private static Job job(boolean visa, String remoteType, Instant posted) {
        return Job.builder().title("Architect").sponsorshipAvailable(visa ? Boolean.TRUE : null)
                .remoteType(remoteType).postedDate(posted).build();
    }

    @Test
    void allBonusesMatchTheWorkedExample() {
        // base 90 + visa 10 + remote 5 + exact role 15 + salary 5 + recent 5 = 130
        var r = engine.compute(job(true, "REMOTE", Instant.now()), result(90, 100, 95));
        assertEquals(130, r.priorityScore());
        assertEquals(PriorityLevel.CRITICAL, r.level());
    }

    @Test
    void noBonusesLeavesScoreUntouched() {
        // base 90, no visa, onsite, weak role/salary, old posting → 90 → MEDIUM band
        var r = engine.compute(job(false, "ONSITE", Instant.now().minus(60, ChronoUnit.DAYS)), result(90, 50, 50));
        assertEquals(90, r.priorityScore());
        assertEquals(PriorityLevel.MEDIUM, r.level());
    }

    @Test
    void visaOnlyBonus() {
        var r = engine.compute(job(true, "ONSITE", Instant.now().minus(60, ChronoUnit.DAYS)), result(90, 50, 50));
        assertEquals(100, r.priorityScore());
        assertEquals(PriorityLevel.HIGH, r.level());
    }

    @Test
    void lowBandBelowEighty() {
        var r = engine.compute(job(false, "ONSITE", null), result(70, 50, 50));
        assertEquals(70, r.priorityScore());
        assertEquals(PriorityLevel.LOW, r.level());
    }

    @Test
    void remoteViaBooleanFlagAlsoCounts() {
        Job j = Job.builder().title("Architect").remote(Boolean.TRUE).build();
        var r = engine.compute(j, result(90, 50, 50));
        assertEquals(95, r.priorityScore()); // +5 remote only
    }

    @Test
    void recentPostingBoundaryIsInclusiveOfSevenDays() {
        var withinWindow = engine.compute(job(false, "ONSITE", Instant.now().minus(6, ChronoUnit.DAYS)), result(90, 50, 50));
        assertEquals(95, withinWindow.priorityScore()); // +5 recent
        var outsideWindow = engine.compute(job(false, "ONSITE", Instant.now().minus(30, ChronoUnit.DAYS)), result(90, 50, 50));
        assertEquals(90, outsideWindow.priorityScore());
    }

    @Test
    void disabledFlagReflectsConstruction() {
        assertTrue(new PriorityEngine(true).isEnabled());
        assertFalse(new PriorityEngine(false).isEnabled());
    }
}
