package ai.careerpilot.service.profile;

/**
 * Raised when the AI extraction cannot be turned into a valid {@link ResumeIntelligence}
 * (no JSON in the response, unparseable, or failing validation). Caught by the service so a
 * failure is recorded in metrics and the source flow (resume upload / preferences) is never
 * affected.
 */
public class ProfileExtractionException extends RuntimeException {
    public ProfileExtractionException(String message) {
        super(message);
    }
}
