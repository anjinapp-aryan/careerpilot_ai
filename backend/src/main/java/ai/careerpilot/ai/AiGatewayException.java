package ai.careerpilot.ai;

/** Thrown only when every configured provider has failed (total outage). */
public class AiGatewayException extends RuntimeException {
    public AiGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
