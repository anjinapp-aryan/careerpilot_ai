package ai.careerpilot.execution.browser.multistep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase F1 — the pre-advance veto.
 *
 * <p>Every test here asserts that automation <em>stops</em>. That asymmetry is the design: the
 * guard can only refuse to leave a page, never authorise it, so the interesting cases are all the
 * ways a page can be unsafe to leave.
 */
class StepAdvanceGuardTest {

    private static final String URL = "https://boards.example.com/jobs/1/apply";

    /** A page in the only state that permits advancing. */
    private static StepAdvanceGuard.Observation clean() {
        return new StepAdvanceGuard.Observation(
                List.of(), List.of(), 1, 1, false, URL, URL, true, true, 1);
    }

    @Test
    @DisplayName("a fully resolved, stable, error-free page may be left")
    void cleanPageAdvances() {
        StepAdvanceGuard.Verdict v = StepAdvanceGuard.evaluate(clean());

        assertThat(v.safe()).isTrue();
        assertThat(v.blockers()).isEmpty();
    }

    @Test
    @DisplayName("a required unresolved control blocks the advance")
    void requiredUnresolvedBlocks() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of("Phone"), List.of(), 1, 1, false, URL, URL, true, true, 1);

        StepAdvanceGuard.Verdict v = StepAdvanceGuard.evaluate(o);

        assertThat(v.safe()).isFalse();
        assertThat(v.blockers()).anyMatch(b -> b.contains("no verified value"));
    }

    @Test
    @DisplayName("visible validation errors block the advance")
    void validationErrorsBlock() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of("Email is required"), 0, 0, false, URL, URL, true, true, 1);

        StepAdvanceGuard.Verdict v = StepAdvanceGuard.evaluate(o);

        assertThat(v.safe()).isFalse();
        assertThat(v.blockers()).anyMatch(b -> b.contains("Email is required"));
    }

    @Test
    @DisplayName("an upload that cannot be read back blocks, whatever the widget claims")
    void unverifiedUploadBlocks() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of(), 2, 1, false, URL, URL, true, true, 1);

        StepAdvanceGuard.Verdict v = StepAdvanceGuard.evaluate(o);

        assertThat(v.safe()).isFalse();
        assertThat(v.blockers()).anyMatch(b -> b.contains("upload verification failed"));
    }

    @Test
    @DisplayName("a CAPTCHA blocks and is never solved")
    void captchaBlocks() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of(), 0, 0, true, URL, URL, true, true, 1);

        StepAdvanceGuard.Verdict v = StepAdvanceGuard.evaluate(o);

        assertThat(v.safe()).isFalse();
        assertThat(v.blockers()).anyMatch(b -> b.contains("never solved"));
    }

    @Test
    @DisplayName("a lost session blocks")
    void sessionTimeoutBlocks() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of(), 0, 0, false, URL, URL, true, false, 1);

        assertThat(StepAdvanceGuard.evaluate(o).blockers())
                .anyMatch(b -> b.contains("session is no longer valid"));
    }

    @Test
    @DisplayName("an unstable page blocks")
    void unstablePageBlocks() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of(), 0, 0, false, URL, URL, false, true, 1);

        assertThat(StepAdvanceGuard.evaluate(o).blockers())
                .anyMatch(b -> b.contains("did not reach a stable state"));
    }

    @Test
    @DisplayName("an unexpected redirect blocks — the page moved under us")
    void redirectBlocks() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of(), 0, 0, false, URL, "https://boards.example.com/session-expired",
                true, true, 1);

        assertThat(StepAdvanceGuard.evaluate(o).blockers())
                .anyMatch(b -> b.contains("unexpected navigation"));
    }

    @Test
    @DisplayName("a query-string or fragment change is not a redirect")
    void queryAndFragmentChangesAreNotRedirects() {
        // Wizards routinely rewrite these as they progress; treating them as redirects would stop
        // every legitimate multi-step form on its first transition.
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of(), 0, 0, false, URL, URL + "?step=2#top", true, true, 1);

        assertThat(StepAdvanceGuard.evaluate(o).safe()).isTrue();
    }

    @Test
    @DisplayName("retries are bounded — the limit escalates to a human, it does not loop")
    void attemptsAreBounded() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of(), List.of(), 0, 0, false, URL, URL, true, true,
                StepAdvanceGuard.MAX_ATTEMPTS + 1);

        assertThat(StepAdvanceGuard.evaluate(o).blockers())
                .anyMatch(b -> b.contains("attempt limit reached"));
    }

    @Test
    @DisplayName("an absent observation is a blocker, not a pass")
    void nullObservationBlocks() {
        StepAdvanceGuard.Verdict v = StepAdvanceGuard.evaluate(null);

        assertThat(v.safe()).isFalse();
        assertThat(v.blockers()).anyMatch(b -> b.contains("cannot prove the page is safe to leave"));
    }

    @Test
    @DisplayName("every failing condition is reported, not just the first")
    void allBlockersAreReported() {
        StepAdvanceGuard.Observation o = new StepAdvanceGuard.Observation(
                List.of("Phone"), List.of("Email is required"), 1, 0, true,
                URL, "https://elsewhere.example.com/x", false, false, 99);

        StepAdvanceGuard.Verdict v = StepAdvanceGuard.evaluate(o);

        // A reviewer fixing one problem should not have to re-run to discover the next seven, so
        // every failing condition is listed. Asserted by category rather than by count: a magic
        // number would break every time a genuinely new check is added, teaching the next person
        // to update the number instead of reading it.
        assertThat(v.safe()).isFalse();
        assertThat(v.blockers()).anyMatch(b -> b.contains("no verified value"));
        assertThat(v.blockers()).anyMatch(b -> b.contains("validation errors"));
        assertThat(v.blockers()).anyMatch(b -> b.contains("upload verification failed"));
        assertThat(v.blockers()).anyMatch(b -> b.contains("never solved"));
        assertThat(v.blockers()).anyMatch(b -> b.contains("session is no longer valid"));
        assertThat(v.blockers()).anyMatch(b -> b.contains("stable state"));
        assertThat(v.blockers()).anyMatch(b -> b.contains("unexpected navigation"));
        assertThat(v.blockers()).anyMatch(b -> b.contains("attempt limit reached"));
    }
}
