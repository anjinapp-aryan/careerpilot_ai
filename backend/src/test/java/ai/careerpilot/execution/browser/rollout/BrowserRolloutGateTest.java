package ai.careerpilot.execution.browser.rollout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12B — the staged-rollout gate. The two properties that make a canary usable rather than
 * merely present are <b>determinism</b> (a user does not oscillate between requests) and
 * <b>monotonicity</b> (raising the percentage only ever adds users). Both are asserted directly
 * here, not inferred.
 */
class BrowserRolloutGateTest {

    private static BrowserRolloutGate gate(int percentage) {
        return new BrowserRolloutGate(percentage, "", "TEST");
    }

    @Test
    void defaultStageExposesNobody() {
        BrowserRolloutGate g = gate(0);
        assertThat(g.isFullyOff()).isTrue();
        for (int i = 0; i < 200; i++) {
            assertThat(g.isEnabledFor(UUID.randomUUID())).isFalse();
        }
    }

    @Test
    void fullRolloutExposesEveryone() {
        BrowserRolloutGate g = gate(100);
        for (int i = 0; i < 200; i++) {
            assertThat(g.isEnabledFor(UUID.randomUUID())).isTrue();
        }
    }

    @Test
    void aNullUserIsNeverAdmitted() {
        // An unattributable execution is precisely the one that must not reach a live employer form.
        assertThat(gate(100).isEnabledFor(null)).isFalse();
    }

    @Test
    void theSameUserAlwaysGetsTheSameAnswer() {
        BrowserRolloutGate g = gate(50);
        UUID user = UUID.randomUUID();
        boolean first = g.isEnabledFor(user);
        for (int i = 0; i < 500; i++) {
            assertThat(g.isEnabledFor(user)).isEqualTo(first);
        }
    }

    /**
     * The property that makes "5% → 25% → 50%" a real progression: everyone already in the cohort
     * stays in it. Without this, raising the percentage would silently drop the very users whose
     * successful runs justified the increase.
     */
    @Test
    void raisingThePercentageOnlyEverAddsUsers() {
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 500; i++) users.add(UUID.randomUUID());

        int[] stages = {5, 25, 50, 100};
        List<UUID> previousCohort = new ArrayList<>();
        for (int stage : stages) {
            BrowserRolloutGate g = gate(stage);
            List<UUID> cohort = users.stream().filter(g::isEnabledFor).toList();
            assertThat(cohort).as("stage %d must retain every user from the previous stage", stage)
                    .containsAll(previousCohort);
            previousCohort = cohort;
        }
        assertThat(previousCohort).hasSize(users.size());
    }

    @Test
    void thePercentageIsApproximatelyHonoured() {
        int total = 4000;
        BrowserRolloutGate g = gate(25);
        long admitted = 0;
        for (int i = 0; i < total; i++) {
            if (g.isEnabledFor(UUID.randomUUID())) admitted++;
        }
        double actual = (admitted * 100.0) / total;
        // Wide band deliberately: this asserts the bucketing is not badly skewed, not that
        // UUID.randomUUID is a perfect uniform source. A broken sign or modulus shows up as 0 or 100.
        assertThat(actual).isBetween(20.0, 30.0);
    }

    @Test
    void bucketsAreAlwaysInRangeIncludingForNegativeHashes() {
        for (int i = 0; i < 2000; i++) {
            assertThat(BrowserRolloutGate.bucketOf(UUID.randomUUID())).isBetween(0, 99);
        }
    }

    @Test
    void anAllowListedUserIsAdmittedEvenAtZeroPercent() {
        UUID insider = UUID.randomUUID();
        BrowserRolloutGate g = new BrowserRolloutGate(0, insider.toString(), "STAGE_1_INTERNAL");
        assertThat(g.isEnabledFor(insider)).isTrue();
        assertThat(g.isEnabledFor(UUID.randomUUID())).isFalse();
        assertThat(g.isFullyOff()).isFalse();
    }

    @Test
    void aMalformedAllowListEntryIsDroppedRatherThanFailingStartup() {
        UUID good = UUID.randomUUID();
        BrowserRolloutGate g = new BrowserRolloutGate(0, "not-a-uuid, " + good + " ,", "STAGE_1");
        assertThat(g.isEnabledFor(good)).isTrue();
        assertThat(g.snapshot()).containsEntry("allowListSize", 1);
    }

    @Test
    void percentageIsClampedToASaneRange() {
        assertThat(gate(-40).percentage()).isZero();
        assertThat(gate(9999).percentage()).isEqualTo(100);
    }

    @Test
    void snapshotNeverLeaksAllowListedUserIds() {
        UUID insider = UUID.randomUUID();
        BrowserRolloutGate g = new BrowserRolloutGate(0, insider.toString(), "STAGE_1");
        assertThat(g.snapshot().toString()).doesNotContain(insider.toString());
    }

    @Test
    void snapshotCountsAdmissionsAndDenials() {
        BrowserRolloutGate g = gate(0);
        g.isEnabledFor(UUID.randomUUID());
        g.isEnabledFor(null);
        assertThat(g.snapshot()).containsEntry("deniedByPercentage", 1L)
                .containsEntry("deniedMissingUser", 1L)
                .containsEntry("admitted", 0L);
    }
}
