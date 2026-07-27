package ai.careerpilot.intent;

import java.util.UUID;

/** Phase 11.1 — the orchestration entry point: message in, classified {@link IntentResult} out. */
public interface IntentEngine {

    IntentResult analyze(UUID userId, String message);
}
