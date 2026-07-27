package ai.careerpilot.capability;

import java.util.Optional;

/** Phase 10.3 — lookup for registered {@link CapabilityDefinition}s, keyed by {@link CapabilityType}. */
public interface CapabilityRegistry {

    Optional<CapabilityDefinition> find(CapabilityType type);

    java.util.List<CapabilityDefinition> all();
}
