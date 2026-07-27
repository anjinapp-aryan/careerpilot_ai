package ai.careerpilot.intent;

import java.util.List;
import java.util.Optional;

/** Phase 11.1 — lookup for registered {@link IntentDefinition}s, keyed by {@link IntentType}. */
public interface IntentRegistry {

    Optional<IntentDefinition> find(IntentType type);

    List<IntentDefinition> all();
}
