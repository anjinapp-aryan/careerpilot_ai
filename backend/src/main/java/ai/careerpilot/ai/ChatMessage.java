package ai.careerpilot.ai;

/**
 * A single turn in a multi-turn LLM conversation, provider-agnostic.
 * {@code role} uses Gemini's vocabulary: "user" or "model".
 */
public record ChatMessage(String role, String content) {
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage model(String content) {
        return new ChatMessage("model", content);
    }
}
