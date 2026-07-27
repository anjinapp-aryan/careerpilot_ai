package ai.careerpilot.capability;

/** Phase 10.3 — analyzes a request and decides whether it needs MCP tool calling. */
public interface CapabilityEngine {

    CapabilityDecision analyze(String message);
}
