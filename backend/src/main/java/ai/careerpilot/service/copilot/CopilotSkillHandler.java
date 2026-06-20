package ai.careerpilot.service.copilot;

/**
 * Base interface for Copilot skill handlers. Each skill assembles
 * RAG context and builds a specialized system prompt + context block.
 */
public interface CopilotSkillHandler {

    /**
     * Returns the skill this handler implements.
     */
    CopilotSkill skill();

    /**
     * Assembles RAG context by fetching relevant user data.
     * Updates skillContext with data and sources.
     */
    void assembleContext(SkillContext context) throws Exception;

    /**
     * Returns the system prompt for this skill.
     */
    String systemPrompt(SkillContext context);

    /**
     * Returns the context block (user data) for this skill.
     */
    String contextBlock(SkillContext context);
}
