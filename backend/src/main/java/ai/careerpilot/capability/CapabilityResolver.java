package ai.careerpilot.capability;

/**
 * Phase 10.3 — decides which {@link CapabilityType} (if any) a free-text user message needs.
 * Returns {@code null} for "no capability matched" (general chat).
 */
public interface CapabilityResolver {

    CapabilityType resolve(String message);
}
