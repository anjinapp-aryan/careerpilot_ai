package ai.careerpilot.execution.browser.form;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 12C — detects that a form rejected our input, from state the discovery script already
 * captured. Pure and deterministic; no browser, no AI.
 *
 * <p>This exists because of a specific failure mode: the submit button is clicked, the page does
 * not navigate, and inline red text appears next to a field. Without detection the run looks like
 * "submitted, no confirmation found" and lands in {@code SUBMIT_UNVERIFIED} — technically honest,
 * but it hides a fixable cause behind an unfixable-looking status. Knowing the form was
 * <em>rejected</em> rather than <em>unconfirmed</em> is the difference between retrying usefully
 * and giving up.
 *
 * <p>Deliberately conservative. It reports an error only on explicit signals — an
 * {@code aria-invalid} field, a live error region with text, or an unambiguous phrase. Loose
 * matching here is worse than none: a false positive aborts a submission that actually worked.
 */
@Component
public class ValidationErrorDetector {

    /** Phrases that unambiguously indicate a rejected field. All matched case-insensitively. */
    private static final List<String> ERROR_PHRASES = List.of(
            "is required",
            "this field is required",
            "required field",
            "please enter",
            "please select",
            "please provide",
            "please complete",
            "must be a valid",
            "enter a valid",
            "invalid email",
            "invalid phone",
            "invalid format",
            "cannot be blank",
            "can't be blank",
            "field is empty",
            "file too large",
            "file is too large",
            "unsupported file type",
            "upload failed");

    /**
     * One rejected field, or a form-level error when {@code fieldSelector} is null.
     *
     * @param fieldSelector the offending control, when the error could be attributed to one
     * @param message       the error text as the employer's page rendered it, never paraphrased
     */
    public record ValidationError(String fieldSelector, String message) {}

    /**
     * The state a page reports after an attempted submit. Built by the discovery script so this
     * class stays browser-free.
     *
     * @param invalidFieldSelectors controls carrying {@code aria-invalid="true"} or {@code :invalid}
     * @param errorMessages         text content of error/alert regions, in DOM order
     * @param urlChanged            whether the URL changed after the submit click
     */
    public record PostSubmitState(List<String> invalidFieldSelectors, List<String> errorMessages,
                                  boolean urlChanged) {
        public PostSubmitState {
            invalidFieldSelectors = invalidFieldSelectors == null ? List.of() : List.copyOf(invalidFieldSelectors);
            errorMessages = errorMessages == null ? List.of() : List.copyOf(errorMessages);
        }

        public static PostSubmitState none() {
            return new PostSubmitState(List.of(), List.of(), false);
        }
    }

    /** All detected errors. Empty means no evidence of rejection — <b>not</b> proof of success. */
    public List<ValidationError> detect(PostSubmitState state) {
        if (state == null) return List.of();
        List<ValidationError> errors = new ArrayList<>();

        for (String selector : state.invalidFieldSelectors()) {
            if (selector != null && !selector.isBlank()) {
                errors.add(new ValidationError(selector, "field reported invalid by the page"));
            }
        }
        for (String message : state.errorMessages()) {
            if (message == null || message.isBlank()) continue;
            String normalised = message.toLowerCase(Locale.ROOT);
            if (ERROR_PHRASES.stream().anyMatch(normalised::contains)) {
                errors.add(new ValidationError(null, message.trim()));
            }
        }
        return List.copyOf(errors);
    }

    /**
     * Whether the form was rejected. Requires a detected error <em>and</em> that the page did not
     * navigate: some ATSes render a residual, already-dismissed error region on the confirmation
     * page itself, and treating that as a rejection would discard a genuinely successful submission.
     */
    public boolean wasRejected(PostSubmitState state) {
        if (state == null || state.urlChanged()) return false;
        return !detect(state).isEmpty();
    }

    /**
     * Which planned fields a set of errors implicates, so a recovery attempt knows what to revisit
     * rather than blindly refilling the whole form. Form-level errors (null selector) match nothing
     * and are reported separately by the caller.
     */
    public List<DiscoveredField> implicatedFields(List<ValidationError> errors, List<DiscoveredField> fields) {
        if (errors == null || fields == null) return List.of();
        List<DiscoveredField> out = new ArrayList<>();
        for (ValidationError error : errors) {
            if (error.fieldSelector() == null) continue;
            fields.stream()
                    .filter(f -> error.fieldSelector().equals(f.selector()))
                    .findFirst()
                    .ifPresent(out::add);
        }
        return List.copyOf(out);
    }
}
