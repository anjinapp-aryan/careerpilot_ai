package ai.careerpilot.ai;

import java.util.List;

/**
 * Thrown only when every configured provider has failed (total outage).
 *
 * Carries the ordered list of provider display names that were attempted so the
 * API layer can return a structured {@code AI_PROVIDER_UNAVAILABLE} payload to the
 * client instead of leaking a stack trace. Never thrown while at least one provider
 * in the chain can still serve the request.
 */
public class AiGatewayException extends RuntimeException {

    private final List<String> providerAttempts;

    public AiGatewayException(String message, Throwable cause) {
        this(message, cause, List.of());
    }

    public AiGatewayException(String message, Throwable cause, List<String> providerAttempts) {
        super(message, cause);
        this.providerAttempts = providerAttempts == null ? List.of() : List.copyOf(providerAttempts);
    }

    /** Ordered display names of every provider that was tried before giving up. */
    public List<String> getProviderAttempts() {
        return providerAttempts;
    }
}
