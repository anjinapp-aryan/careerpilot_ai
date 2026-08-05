package ai.careerpilot.execution.verification.evidence;

/**
 * Phase 0 — one piece of evidence about a submission attempt. Immutable and self-describing:
 * {@code detail} records what was actually observed (e.g. the matched confirmation phrase, the
 * extracted reference) so the audit trail shows its own methodology rather than only a verdict —
 * the same discipline {@code VerificationResult.method()} already follows.
 */
public record VerificationSignal(SignalType type, SignalStrength strength, String detail) {

    public static VerificationSignal applicationId(String reference) {
        return new VerificationSignal(SignalType.APPLICATION_ID, SignalStrength.DECISIVE,
                "extracted reference: " + reference);
    }

    public static VerificationSignal successDom(String matchedPhrase) {
        return new VerificationSignal(SignalType.SUCCESS_DOM, SignalStrength.STRONG,
                "confirmation phrase present: \"" + matchedPhrase + "\"");
    }

    public static VerificationSignal urlTransition(String url) {
        return new VerificationSignal(SignalType.URL_TRANSITION, SignalStrength.STRONG,
                "navigated to success route: " + url);
    }

    public static VerificationSignal networkResponse(String detail) {
        return new VerificationSignal(SignalType.NETWORK_RESPONSE, SignalStrength.STRONG, detail);
    }

    public static VerificationSignal emailConfirmation(String detail) {
        return new VerificationSignal(SignalType.EMAIL_CONFIRMATION, SignalStrength.STRONG, detail);
    }

    public static VerificationSignal screenshot(String storageKey) {
        return new VerificationSignal(SignalType.SCREENSHOT, SignalStrength.CORROBORATING,
                "screenshot stored: " + storageKey);
    }

    public static VerificationSignal errorState(String matchedPhrase) {
        return new VerificationSignal(SignalType.ERROR_STATE, SignalStrength.STRONG,
                "failure indicator present: \"" + matchedPhrase + "\"");
    }
}
