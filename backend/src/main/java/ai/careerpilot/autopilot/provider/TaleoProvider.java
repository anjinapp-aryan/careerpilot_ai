package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 7.16 — recognizes Taleo-hosted application URLs. Submission not integrated (human review),
 * same shape as {@link WorkdayProvider}. Explicit bean name — this codebase has hit bean-name
 * collisions between {@code autopilot.provider.*} and {@code jobdiscovery.provider.*} simple class
 * names three times already.
 */
@Component("autopilotTaleoProvider")
public class TaleoProvider extends AbstractAtsProvider {
    @Override public String name() { return "taleo"; }
    @Override protected List<String> hostPatterns() { return List.of("taleo.net", "tbe.taleo.net"); }
}
