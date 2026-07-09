package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.ats.AbstractStubConnector;
import org.springframework.stereotype.Component;

/** Phase 2E.3 — inert BambooHR ATS connector stub. */
@Component
public class BambooHrConnector extends AbstractStubConnector {
    @Override public String name() { return "bamboohr"; }
    @Override protected String hostToken() { return "bamboohr.com"; }
}
