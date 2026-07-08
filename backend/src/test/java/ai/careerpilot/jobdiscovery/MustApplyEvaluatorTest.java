package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2C-2 — MUST_APPLY is a strict AND: every condition (role >=95, exact role, preference >=95,
 * visa supported, recent posting) must hold. These tests remove one condition at a time to pin that
 * each is genuinely required, and confirm the all-true case.
 */
class MustApplyEvaluatorTest {

    private final MustApplyEvaluator evaluator = new MustApplyEvaluator();

    private static JobScoring.ScoreResultV2 result(int role, int workMode, int matchedRoleCount) {
        return new JobScoring.ScoreResultV2(95, List.of(), List.of(),
                new JobScoring.ScoreBreakdown(90, 90, role, 90, 90, 100, workMode, 0), "HIGH", 4, matchedRoleCount);
    }

    private static Job job(boolean visa, Instant posted) {
        return Job.builder().title("Solution Architect")
                .sponsorshipAvailable(visa ? Boolean.TRUE : null).postedDate(posted).build();
    }

    @Test
    void allConditionsMetIsMustApply() {
        assertTrue(evaluator.isMustApply(job(true, Instant.now()), result(100, 100, 1)));
    }

    @Test
    void roleBelowNinetyFiveIsNotMustApply() {
        assertFalse(evaluator.isMustApply(job(true, Instant.now()), result(94, 100, 1)));
    }

    @Test
    void noExactRoleMatchIsNotMustApply() {
        assertFalse(evaluator.isMustApply(job(true, Instant.now()), result(100, 100, 0)));
    }

    @Test
    void preferenceBelowNinetyFiveIsNotMustApply() {
        assertFalse(evaluator.isMustApply(job(true, Instant.now()), result(100, 94, 1)));
    }

    @Test
    void missingVisaSupportIsNotMustApply() {
        assertFalse(evaluator.isMustApply(job(false, Instant.now()), result(100, 100, 1)));
    }

    @Test
    void stalePostingIsNotMustApply() {
        assertFalse(evaluator.isMustApply(job(true, Instant.now().minus(30, ChronoUnit.DAYS)), result(100, 100, 1)));
    }

    @Test
    void nullPostingDateIsNotMustApply() {
        assertFalse(evaluator.isMustApply(job(true, null), result(100, 100, 1)));
    }
}
