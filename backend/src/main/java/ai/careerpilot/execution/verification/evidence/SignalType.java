package ai.careerpilot.execution.verification.evidence;

/**
 * Phase 0 (Browser Automation Platform) — the families of evidence that can support a claim that
 * an application was genuinely submitted.
 *
 * <p>Only the first two plus {@link #ERROR_STATE} are <b>producible today</b>: they are derived
 * from the post-submit page content that {@code GreenhouseConnector}/{@code LeverConnector}
 * already capture. The remainder are declared because the confidence ladder is defined in terms
 * of them and later phases will emit them (URL transition and network response need the adapter
 * layer; email confirmation needs a mailbox integration that does not exist — {@code
 * EmailIntelligenceService} is currently inert). A signal type with no producer simply never
 * appears in an {@link EvidenceBundle}; it is never synthesised to pad confidence.
 */
public enum SignalType {

    /** A vendor-issued application/confirmation reference was extracted. Cannot be produced by an error page. */
    APPLICATION_ID,

    /** A positive success assertion in the post-submit DOM (e.g. "your application has been received"). */
    SUCCESS_DOM,

    /** Navigation to a vendor-declared success route (e.g. Lever's {@code /thanks}). Not produced until the adapter layer exists. */
    URL_TRANSITION,

    /** A 2xx from the vendor's submit endpoint, captured by response interception. Not produced until the adapter layer exists. */
    NETWORK_RESPONSE,

    /** A confirmation email classified as an application receipt. No mailbox integration exists today. */
    EMAIL_CONFIRMATION,

    /** A stored screenshot. Human-auditable only — never machine-adjudicated as proof on its own. */
    SCREENSHOT,

    /**
     * A detected failure state in the post-submit page (validation errors, "something went wrong").
     * Present-and-negative: forces {@link ConfidenceLevel#NONE} regardless of any other signal.
     */
    ERROR_STATE
}
