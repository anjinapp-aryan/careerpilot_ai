package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 7.4 — recognizes SmartRecruiters-hosted application URLs. Submission not integrated
 * (human review). Explicit bean name: the default ("smartRecruitersProvider") collides with the
 * unrelated job-discovery {@code ai.careerpilot.jobdiscovery.provider.SmartRecruitersProvider}
 * and fails context startup.
 */
@Component("autopilotSmartRecruitersProvider")
public class SmartRecruitersProvider extends AbstractAtsProvider {
    @Override public String name() { return "smartrecruiters"; }
    @Override protected List<String> hostPatterns() { return List.of("smartrecruiters.com", "jobs.smartrecruiters"); }
}
