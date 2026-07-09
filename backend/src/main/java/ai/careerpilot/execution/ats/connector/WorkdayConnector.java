package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.ats.AbstractStubConnector;
import org.springframework.stereotype.Component;

/** Phase 2E.3 — inert Workday ATS connector stub. */
@Component
public class WorkdayConnector extends AbstractStubConnector {
    @Override public String name() { return "workday"; }
    @Override protected String hostToken() { return "myworkdayjobs.com"; }
}
