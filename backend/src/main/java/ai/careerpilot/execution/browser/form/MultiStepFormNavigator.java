package ai.careerpilot.execution.browser.form;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Phase 12C — decides what to click on a multi-step application form. Pure and deterministic.
 *
 * <p><b>The distinction this class exists to protect is Next vs. Submit.</b> Clicking a button
 * labelled "Submit" when we believed it said "Continue" delivers a half-filled application to a
 * real employer, irreversibly. So the rule is asymmetric and deliberately not symmetric-looking:
 * <ul>
 *   <li>Advancing (Next/Continue/Review) is inferred permissively — a wrong guess costs a wasted
 *       click on a page that will simply not advance.</li>
 *   <li>Submitting requires an <em>unambiguous</em> submit label and no advance candidate present.
 *       Anything ambiguous returns {@link Action#UNCLEAR}, which the engine treats as a stop.</li>
 * </ul>
 *
 * <p>When a page offers both "Back" and "Next", Back is never chosen. There is no scenario in a
 * forward-only application flow where reversing is the right automated move, and a navigator that
 * can go backwards can loop.
 */
@Component
public class MultiStepFormNavigator {

    private static final List<String> ADVANCE_LABELS = List.of(
            "next", "continue", "next step", "save and continue", "save & continue",
            "proceed", "review", "review application", "continue to", "go to next");

    private static final List<String> SUBMIT_LABELS = List.of(
            "submit application", "submit my application", "send application",
            "submit", "apply now", "send my application", "finish and submit",
            "complete application");

    private static final List<String> BACK_LABELS = List.of("back", "previous", "go back", "return");

    public enum Action {
        /** Click the returned button to move to the next step. */
        ADVANCE,
        /** Click the returned button to submit — the terminal, irreversible action. */
        SUBMIT,
        /** No actionable button found, or the choice is ambiguous. The engine must stop. */
        UNCLEAR
    }

    /**
     * A clickable control observed on the page.
     *
     * @param selector  what to click
     * @param label     visible text or accessible name
     * @param enabled   whether it is currently clickable
     */
    public record Button(String selector, String label, boolean enabled) {}

    /** The decision, with the reason recorded for evidence. */
    public record Decision(Action action, Button button, String reason) {
        public static Decision unclear(String reason) {
            return new Decision(Action.UNCLEAR, null, reason);
        }
    }

    /**
     * Decide the next click.
     *
     * <p>Advance is checked <em>before</em> submit on purpose: a multi-step form's early pages
     * often contain a disabled or hidden final "Submit" alongside an active "Next", and preferring
     * submit there would try to send an incomplete application.
     */
    public Decision decide(List<Button> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return Decision.unclear("no buttons found on the page");
        }
        List<Button> usable = buttons.stream()
                .filter(b -> b != null && b.enabled() && b.label() != null && !b.label().isBlank())
                .toList();
        if (usable.isEmpty()) {
            return Decision.unclear("no enabled, labelled buttons found");
        }

        Button advance = firstMatching(usable, ADVANCE_LABELS);
        if (advance != null) {
            return new Decision(Action.ADVANCE, advance, "advance control found: \"" + advance.label() + "\"");
        }

        List<Button> submits = usable.stream().filter(b -> matches(b.label(), SUBMIT_LABELS)).toList();
        if (submits.size() == 1) {
            return new Decision(Action.SUBMIT, submits.get(0),
                    "single unambiguous submit control: \"" + submits.get(0).label() + "\"");
        }
        if (submits.size() > 1) {
            // Two things that both look like submit is precisely when NOT to guess.
            return Decision.unclear("multiple submit-like controls found (" + submits.size() + ") — ambiguous");
        }

        if (firstMatching(usable, BACK_LABELS) != null) {
            return Decision.unclear("only a back/previous control is available — refusing to navigate backwards");
        }
        return Decision.unclear("no advance or submit control recognised");
    }

    /**
     * Whether the page still has steps left. Used only for reporting and loop bounding — never to
     * decide whether to submit, which is {@link #decide}'s job alone.
     */
    public boolean looksLikeFinalStep(List<Button> buttons) {
        Decision decision = decide(buttons);
        return decision.action() == Action.SUBMIT;
    }

    private static Button firstMatching(List<Button> buttons, List<String> labels) {
        for (Button b : buttons) {
            if (matches(b.label(), labels)) return b;
        }
        return null;
    }

    /**
     * Exact match on the normalised label, then a prefix match. Substring matching in both
     * directions is avoided: "Submit" is a substring of "Do not submit without reviewing".
     */
    private static boolean matches(String label, List<String> candidates) {
        String l = normalise(label);
        if (l.isEmpty()) return false;
        for (String c : candidates) {
            if (l.equals(c)) return true;
        }
        for (String c : candidates) {
            if (l.startsWith(c + " ") || l.startsWith(c + ":")) return true;
        }
        return false;
    }

    private static String normalise(String label) {
        if (label == null) return "";
        return label.toLowerCase(Locale.ROOT)
                .replaceAll("[\\u2192\\u00bb>]", " ")   // arrows and chevrons used as affordances
                .replaceAll("[^a-z0-9&: ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
