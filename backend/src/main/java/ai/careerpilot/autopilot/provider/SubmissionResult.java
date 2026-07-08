package ai.careerpilot.autopilot.provider;

/** Phase 7.4 — outcome of a submission attempt, plus which provider produced it and why. */
public record SubmissionResult(SubmissionStatus status, String provider, String externalReference, String reason) {

    public static SubmissionResult humanReview(String provider, String reason) {
        return new SubmissionResult(SubmissionStatus.HUMAN_REVIEW, provider, null, reason);
    }
}
