package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/** Phase 7.4 — recognizes Workday-hosted application URLs. Submission not integrated (human review). */
@Component
public class WorkdayProvider extends AbstractAtsProvider {
    @Override public String name() { return "workday"; }
    @Override protected List<String> hostPatterns() { return List.of("myworkdayjobs.com", "workday.com"); }
}
