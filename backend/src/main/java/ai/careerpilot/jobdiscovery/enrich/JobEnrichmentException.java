package ai.careerpilot.jobdiscovery.enrich;

/**
 * Thrown when a job cannot be enriched — empty input or the model returned nothing usable.
 * Callers ({@link JobAiEnrichmentService}) record the failure and leave the job un-enriched
 * (it stays on the work list for a future pass); it never propagates to the discovery run.
 */
public class JobEnrichmentException extends RuntimeException {
    public JobEnrichmentException(String message) {
        super(message);
    }
}
