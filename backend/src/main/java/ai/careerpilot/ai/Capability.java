package ai.careerpilot.ai;

/**
 * A capability an LLM call needs, independent of any specific vendor/model name.
 * Currently consumed only by {@link ai.careerpilot.ai.provider.OpenRouterProvider}'s
 * internal model pool ({@code ai.gateway.open-router.capabilities.<name>.preferred}) —
 * business logic never references a model name directly, only a capability.
 *
 * <p>Not every value is necessarily populated with a configured model today (e.g.
 * {@link #VISION}/{@link #EMBEDDING} — see CLAUDE.md's "DO NOT ADD" list for why no
 * vision/embedding/image/speech/rerank model is currently registered); the enum exists
 * as the full taxonomy so a future capability can be wired in via config alone.</p>
 */
public enum Capability {
    REASONING,
    CODING,
    CHAT,
    LIGHTWEIGHT,
    VISION,
    EMBEDDING,
    FUNCTION_CALLING,
    LONG_CONTEXT
}
