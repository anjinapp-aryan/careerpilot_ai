package ai.careerpilot.ai;

public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
