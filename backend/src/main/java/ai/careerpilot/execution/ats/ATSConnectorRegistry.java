package ai.careerpilot.execution.ats;

import ai.careerpilot.domain.Job;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 2E.3 — routes a {@link Job} to the ATS connector that recognises it. Spring injects every
 * {@link ATSConnector} bean; {@link #detect(Job)} returns the first that both matches the job URL
 * and {@link ATSConnector#isConfigured()}-guards to real work later. In the 2E build no connector is
 * configured, so {@link #firstConfigured()} is always empty and only {@link #detect} (side-effect
 * free) ever returns a match — used purely to label the execution's {@code execution_type}/provider.
 */
@Component
public class ATSConnectorRegistry {

    private final List<ATSConnector> connectors;

    public ATSConnectorRegistry(List<ATSConnector> connectors) {
        this.connectors = List.copyOf(connectors);
    }

    /** The connector whose {@code detect()} matches this job, or null. Does NOT require configured. */
    public ATSConnector detect(Job job) {
        return connectors.stream().filter(c -> c.detect(job)).findFirst().orElse(null);
    }

    /** All registered connectors (for diagnostics). */
    public List<ATSConnector> all() {
        return connectors;
    }

    /** Count of connectors that are actually wired for real submission. Always 0 in the 2E build. */
    public long configuredCount() {
        return connectors.stream().filter(ATSConnector::isConfigured).count();
    }
}
