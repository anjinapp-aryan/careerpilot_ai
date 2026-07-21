package ai.careerpilot.execution.verification;

/**
 * Phase 7.16.1 — the result of one {@code ATSConnector.verifySubmission} call. {@code method}
 * names HOW the check was made (e.g. "CONFIRMATION_REFERENCE_PRESENT", "NO_CONNECTOR",
 * "CONNECTOR_ERROR") so the evidence trail can show its own methodology, not just a verdict.
 */
public record VerificationResult(VerificationStatus status, String method, String reason) {

    public static VerificationResult verified(String method, String reason) {
        return new VerificationResult(VerificationStatus.VERIFIED, method, reason);
    }

    public static VerificationResult notVerified(String method, String reason) {
        return new VerificationResult(VerificationStatus.NOT_VERIFIED, method, reason);
    }

    public static VerificationResult unableToVerify(String method, String reason) {
        return new VerificationResult(VerificationStatus.UNABLE_TO_VERIFY, method, reason);
    }

    public static VerificationResult unknown(String method, String reason) {
        return new VerificationResult(VerificationStatus.UNKNOWN, method, reason);
    }
}
