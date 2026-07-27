package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 11.3 — the default {@link ResultMerger}. Builds a genuinely structured {@code
 * Map<CapabilityType, Object>} (successful capabilities only, each value the raw per-tool output
 * map from its {@link ExecutionResult}) rather than concatenating raw strings — per the phase
 * spec's explicit "do not concatenate raw text, create a structured context object" requirement.
 * {@code textBlock} is a rendering of that same structure for a future LLM prompt to consume,
 * including failed capabilities (as an explicit "unavailable" note) so a synthesis step can
 * still acknowledge a gap rather than silently omitting it.
 */
public class DefaultResultMerger implements ResultMerger {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public MergedExecutionContext merge(Map<CapabilityType, ExecutionResult> results) {
        Map<CapabilityType, Object> perCapability = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();

        results.forEach((type, result) -> {
            text.append("### ").append(type).append('\n');
            if (result.success()) {
                perCapability.put(type, result.toolResults());
                try {
                    text.append(mapper.writeValueAsString(result.toolResults()));
                } catch (Exception e) {
                    text.append(result.toolResults());
                }
            } else {
                text.append("unavailable: ").append(result.error());
            }
            text.append("\n\n");
        });

        return new MergedExecutionContext(Map.copyOf(perCapability), text.toString());
    }
}
