package ai.careerpilot.ai;

import java.util.List;
import java.util.Map;

/**
 * Implemented by an {@link LlmProvider} that internally represents a <em>pool</em> of models
 * (e.g. {@code OpenRouterProvider}) rather than one fixed model per bean. {@link
 * AiGatewayService#providerStatuses()} checks for this interface (not any concrete provider
 * class — Open/Closed: a future pool-style provider needs zero gateway changes) and, when
 * present, attaches the per-model breakdown under the outer provider's diagnostics entry.
 */
public interface ModelPoolProvider {

    /** Per-model diagnostics: id, health, circuit state, avg latency, capability tags, etc. */
    List<Map<String, Object>> modelPoolStatuses();
}
