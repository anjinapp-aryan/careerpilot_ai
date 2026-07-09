package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.ats.AbstractStubConnector;
import org.springframework.stereotype.Component;

/** Phase 2E.3 — inert LinkedIn (Easy Apply) connector stub. */
@Component
public class LinkedInConnector extends AbstractStubConnector {
    @Override public String name() { return "linkedin"; }
    @Override protected String hostToken() { return "linkedin.com"; }
}
