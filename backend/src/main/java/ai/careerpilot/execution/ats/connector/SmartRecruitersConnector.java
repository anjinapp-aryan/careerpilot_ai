package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.ats.AbstractStubConnector;
import org.springframework.stereotype.Component;

/** Phase 2E.3 — inert SmartRecruiters ATS connector stub. */
@Component
public class SmartRecruitersConnector extends AbstractStubConnector {
    @Override public String name() { return "smartrecruiters"; }
    @Override protected String hostToken() { return "smartrecruiters.com"; }
}
