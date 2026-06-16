package ai.careerpilot.ai;

import java.util.Map;

/**
 * Provider-agnostic LLM facade. Services/agents must depend on this, never on a concrete vendor SDK.
 * In CareerPilot, heavy multi-agent orchestration lives in the Python agent-service; the Java side
 * uses this for lightweight inline LLM calls (e.g., summarization, embed text for retrieval).
 */
public interface AIProvider {
    String generateResponse(String prompt, String system, double temperature);
    Map<String, Object> generateStructuredResponse(String prompt, Map<String, Object> jsonSchema, String system);
    Map<String, Object> generateJson(String prompt, String system);
    double estimateCost(int inputTokens, int outputTokens);
}
