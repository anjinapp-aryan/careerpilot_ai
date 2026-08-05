package ai.careerpilot.execution.browser.multistep;

import ai.careerpilot.domain.ExecutionStep;

import java.util.List;
import java.util.Optional;

/**
 * Phase F1 — decides, from persisted step state alone, what a run is allowed to do next.
 *
 * <p>Pure, stateless, deterministic and thread-safe. No browser, no repository, no clock. This is
 * deliberately where the approval guarantee lives, because the guarantee is a statement about
 * <em>state</em>, not about browser mechanics: it can therefore be proven by a table of records in
 * a test rather than inferred from an integration run.
 *
 * <p><b>The invariant:</b> a page may be filled only when every earlier page is APPROVED and no
 * page is currently awaiting approval. That single rule is what makes "pages 2, 3 and 4 auto-filled
 * after one approval" unrepresentable rather than merely discouraged.
 */
public final class StepProgressPolicy {

    private StepProgressPolicy() {
    }

    /** What the orchestrator should do next. */
    public enum Action {
        /** Fill the returned step number. Every prior step is approved. */
        FILL_STEP,
        /** A step is parked on a human. Do nothing — emphatically, do not fill ahead. */
        WAIT_FOR_APPROVAL,
        /** Every step is approved and the last one was terminal: submission may be attempted. */
        READY_TO_SUBMIT,
        /** A step was rejected, blocked or failed. The run is over until a human intervenes. */
        STOP
    }

    public record Decision(Action action, int stepNumber, String reason) {}

    /**
     * Decide from the persisted steps of one execution.
     *
     * @param steps       all known steps, any order
     * @param maxSteps    hard ceiling on wizard length; reaching it stops rather than looping
     */
    public static Decision decide(List<ExecutionStep> steps, int maxSteps) {
        List<ExecutionStep> ordered = steps == null ? List.of()
                : steps.stream().sorted(java.util.Comparator.comparingInt(ExecutionStep::getStepNumber)).toList();

        if (ordered.isEmpty()) {
            return new Decision(Action.FILL_STEP, 1, "no steps recorded yet — starting at page 1");
        }

        // Any terminal-bad state stops the run outright. Checked first: a later approved page must
        // never mask an earlier rejection.
        Optional<ExecutionStep> bad = ordered.stream()
                .filter(s -> ExecutionStep.STATUS_REJECTED.equals(s.getStatus())
                        || ExecutionStep.STATUS_BLOCKED.equals(s.getStatus())
                        || ExecutionStep.STATUS_FAILED.equals(s.getStatus()))
                .findFirst();
        if (bad.isPresent()) {
            return new Decision(Action.STOP, bad.get().getStepNumber(),
                    "step " + bad.get().getStepNumber() + " is " + bad.get().getStatus()
                            + " — a human must intervene before this run continues");
        }

        // Anything awaiting approval halts everything. This is the rule that makes filling ahead
        // impossible, not merely discouraged.
        Optional<ExecutionStep> waiting = ordered.stream()
                .filter(ExecutionStep::isAwaitingApproval).findFirst();
        if (waiting.isPresent()) {
            return new Decision(Action.WAIT_FOR_APPROVAL, waiting.get().getStepNumber(),
                    "step " + waiting.get().getStepNumber()
                            + " is awaiting human approval — no page may be filled until it is decided");
        }

        // A gap means a page was never recorded. Refuse rather than guessing which one to fill:
        // a missing step is a page nobody reviewed.
        for (int i = 0; i < ordered.size(); i++) {
            int expected = i + 1;
            if (ordered.get(i).getStepNumber() != expected) {
                return new Decision(Action.STOP, expected,
                        "step " + expected + " is missing from the record — refusing to continue "
                                + "with an unreviewed page in the sequence");
            }
        }

        ExecutionStep last = ordered.get(ordered.size() - 1);
        if (!last.isApproved()) {
            return new Decision(Action.STOP, last.getStepNumber(),
                    "step " + last.getStepNumber() + " is in an unexpected state: " + last.getStatus());
        }
        if (last.isFinalStep()) {
            return new Decision(Action.READY_TO_SUBMIT, last.getStepNumber(),
                    "all " + ordered.size() + " step(s) approved and the last is terminal");
        }
        int next = last.getStepNumber() + 1;
        if (next > maxSteps) {
            // A wizard longer than any real form is far likelier to be a navigation loop.
            return new Decision(Action.STOP, last.getStepNumber(),
                    "step limit reached (" + maxSteps + ") — stopping rather than navigating further");
        }
        return new Decision(Action.FILL_STEP, next,
                "steps 1.." + last.getStepNumber() + " approved — page " + next + " may be filled");
    }

    /**
     * How many already-approved pages must be replayed to reach {@code targetStep}.
     *
     * <p>Replay is what substitutes for a held session. It is safe only because fills are
     * deterministic — every value comes from a verified profile field or a human-approved answer —
     * so replaying page 1 types exactly what the reviewer already saw and approved. SUBMIT is never
     * clicked during a replay; only ADVANCE, and only this many times.
     */
    public static int replayAdvances(int targetStep) {
        return Math.max(0, targetStep - 1);
    }
}
