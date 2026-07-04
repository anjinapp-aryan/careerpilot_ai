package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.ats.AbstractStubConnector;
import org.springframework.stereotype.Component;

/** Phase 2E.3 — inert Lever ATS connector stub. */
@Component
public class LeverConnector extends AbstractStubConnector {
    @Override public String name() { return "lever"; }
    @Override protected String hostToken() { return "lever.co"; }
}
