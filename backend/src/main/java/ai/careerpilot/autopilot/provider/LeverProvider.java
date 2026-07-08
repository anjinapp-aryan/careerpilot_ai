package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/** Phase 7.4 — recognizes Lever-hosted application URLs. Submission not integrated (human review). */
@Component
public class LeverProvider extends AbstractAtsProvider {
    @Override public String name() { return "lever"; }
    @Override protected List<String> hostPatterns() { return List.of("jobs.lever.co", "lever.co"); }
}
