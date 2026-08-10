package ai.careerpilot.jobdiscovery;

/**
 * Global Job Discovery Expansion — a richer, 4-state sponsorship signal alongside the existing
 * {@link ai.careerpilot.domain.Job#getSponsorshipAvailable()} boolean (untouched). Distinguishes
 * a firm commitment from a bare mention so the UI and the international ranking's visa-probability
 * component never treat "mentions visa" and "explicitly sponsors" as the same evidence.
 *
 * <p><b>UNKNOWN must never be upgraded to CONFIRMED or MENTIONED.</b> No signal means no signal —
 * never inferred from country, company size, seniority, or another job at the same company.
 */
public enum SponsorshipSignal {
    /** Reliable, explicit evidence the employer sponsors (e.g. "we sponsor H-1B visas"). */
    CONFIRMED,
    /** The posting raises the topic without establishing certainty (e.g. "visa support available"). */
    MENTIONED,
    /** No reliable signal either way — the honest default, never displayed as a positive claim. */
    UNKNOWN,
    /** The posting explicitly states sponsorship/work authorization is unavailable. */
    NOT_SUPPORTED
}
