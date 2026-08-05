package ai.careerpilot.execution.verification.evidence;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 0 — extracts real {@link VerificationSignal}s from the post-submit page content that
 * {@code GreenhouseConnector}/{@code LeverConnector} already capture.
 *
 * <p>This replaces the previous entire verification rule, which was "the captured page is longer
 * than 50 characters ⇒ VERIFIED". That heuristic certified any rendered page — including an error
 * page — as a successful submission.
 *
 * <p>Deliberately limited to what the captured string can actually support: a positive
 * confirmation phrase ({@link SignalType#SUCCESS_DOM}), an extractable reference ({@link
 * SignalType#APPLICATION_ID}), and failure indicators ({@link SignalType#ERROR_STATE}). URL
 * transitions and network responses are stronger signals but need the live page handle, which
 * arrives with the adapter layer in a later phase — they are not approximated here.
 *
 * <p>Error detection is intentionally slightly eager. A false {@code ERROR_STATE} costs an
 * unnecessary human confirmation; a missed one lets the platform claim a submission that never
 * happened. The first failure mode is recoverable, the second is not.
 */
@Service
public class ConfirmationPageAnalyzer {

    /** Phrases an ATS confirmation view actually renders. Matched against tag-stripped, lowercased text. */
    private static final List<String> SUCCESS_PHRASES = List.of(
            "thank you for applying",
            "thanks for applying",
            "thank you for your application",
            "thank you for your interest",
            "application received",
            "application has been received",
            "we have received your application",
            "we've received your application",
            "your application has been submitted",
            "application was submitted",
            "application submitted",
            "application complete",
            "successfully applied",
            "successfully submitted");

    /** Failure indicators. Kept specific enough not to fire on ordinary form markup. */
    private static final List<String> ERROR_PHRASES = List.of(
            "something went wrong",
            "there was a problem",
            "there was an error",
            "an error occurred",
            "unable to submit",
            "submission failed",
            "failed to submit",
            "please correct the",
            "please fix the",
            "could not be submitted");

    /**
     * Vendor-issued reference, e.g. "Confirmation number: 4XJ-88213" or "Application ID #A19822".
     * Requires an explicit label — a bare alphanumeric token anywhere on the page is not a reference.
     */
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:confirmation|application|reference|tracking)\\s*(?:number|code|id|no\\.?|#)?\\s*[:#]\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9\\-_]{3,63})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * @param pageContent the captured post-submit page. {@code null}/blank yields an empty bundle —
     *                    which adjudicates to {@link ConfidenceLevel#NONE}, never to success.
     */
    public EvidenceBundle analyze(String pageContent) {
        if (pageContent == null || pageContent.isBlank()) {
            return EvidenceBundle.empty();
        }
        String text = normalize(pageContent);
        EvidenceBundle bundle = EvidenceBundle.empty();

        for (String phrase : ERROR_PHRASES) {
            if (text.contains(phrase)) {
                // Short-circuit: a detected failure makes any positive signal irrelevant.
                return bundle.with(VerificationSignal.errorState(phrase));
            }
        }

        Matcher reference = REFERENCE.matcher(text);
        if (reference.find()) {
            bundle = bundle.with(VerificationSignal.applicationId(reference.group(1)));
        }

        for (String phrase : SUCCESS_PHRASES) {
            if (text.contains(phrase)) {
                bundle = bundle.with(VerificationSignal.successDom(phrase));
                break; // one confirmation phrase is one signal, not N
            }
        }
        return bundle;
    }

    /** Strip tags and collapse whitespace so a phrase split across markup still matches. */
    private static String normalize(String html) {
        String stripped = TAGS.matcher(html).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").toLowerCase();
    }
}
