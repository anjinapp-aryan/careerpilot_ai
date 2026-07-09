package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 7.4 — recognizes Greenhouse-hosted application URLs. Submission not integrated (human review).
 * Explicit bean name: the default ("greenhouseProvider") collides with the unrelated job-discovery
 * {@code ai.careerpilot.jobdiscovery.provider.GreenhouseProvider} and fails context startup.
 */
@Component("autopilotGreenhouseProvider")
public class GreenhouseProvider extends AbstractAtsProvider {
    @Override public String name() { return "greenhouse"; }
    @Override protected List<String> hostPatterns() { return List.of("greenhouse.io", "boards.greenhouse"); }
}
