package ai.careerpilot.service.copilot;

import ai.careerpilot.service.CareerContextRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation for all Copilot skill handlers.
 * Provides common utilities for context assembly and truncation.
 */
public abstract class AbstractSkillHandler implements CopilotSkillHandler {

    protected static final Logger log = LoggerFactory.getLogger(AbstractSkillHandler.class);
    protected static final int MAX_RESUME_CHARS = 6000;
    protected static final int MAX_JOB_CHARS = 4000;
    protected static final int MAX_SKILL_CHARS = 2000;

    protected final CareerContextRetriever retriever;

    public AbstractSkillHandler(CareerContextRetriever retriever) {
        this.retriever = retriever;
    }

    protected String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n[…truncated]";
    }

    protected String nullSafe(String s) {
        return s == null || s.isBlank() ? "n/a" : s;
    }

    protected String orNa(Integer v) {
        return v == null ? "n/a" : v.toString();
    }
}
