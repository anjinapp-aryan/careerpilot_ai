package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.ats.AbstractStubConnector;
import org.springframework.stereotype.Component;

/** Phase 2E.3 — inert Ashby ATS connector stub. */
@Component
public class AshbyConnector extends AbstractStubConnector {
    @Override public String name() { return "ashby"; }
    @Override protected String hostToken() { return "ashbyhq.com"; }
}
