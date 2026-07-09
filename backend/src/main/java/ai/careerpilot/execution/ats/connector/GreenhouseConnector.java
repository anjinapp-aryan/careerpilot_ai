package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.execution.ats.AbstractStubConnector;
import org.springframework.stereotype.Component;

/** Phase 2E.3 — inert Greenhouse ATS connector stub. */
@Component
public class GreenhouseConnector extends AbstractStubConnector {
    @Override public String name() { return "greenhouse"; }
    @Override protected String hostToken() { return "greenhouse.io"; }
}
