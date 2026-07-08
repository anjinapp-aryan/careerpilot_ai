package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/** Phase 7.4 — recognizes Greenhouse-hosted application URLs. Submission not integrated (human review). */
@Component
public class GreenhouseProvider extends AbstractAtsProvider {
    @Override public String name() { return "greenhouse"; }
    @Override protected List<String> hostPatterns() { return List.of("greenhouse.io", "boards.greenhouse"); }
}
