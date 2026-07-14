package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 7.4 — recognizes Ashby-hosted application URLs. Submission not integrated (human review).
 * Explicit bean name: the default ("ashbyProvider") collides with the unrelated job-discovery
 * {@code ai.careerpilot.jobdiscovery.provider.AshbyProvider} and fails context startup.
 */
@Component("autopilotAshbyProvider")
public class AshbyProvider extends AbstractAtsProvider {
    @Override public String name() { return "ashby"; }
    @Override protected List<String> hostPatterns() { return List.of("jobs.ashbyhq.com", "ashbyhq.com"); }
}
