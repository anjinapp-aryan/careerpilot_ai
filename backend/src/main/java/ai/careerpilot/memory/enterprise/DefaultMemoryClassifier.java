package ai.careerpilot.memory.enterprise;

/**
 * Phase 11.4 — the default {@link MemoryClassifier}: keyword rules, styled after {@code
 * ai.careerpilot.intent.KeywordIntentResolver}. Order matters — more specific checks run first.
 * Content matching nothing specific lands in {@link MemoryType#WORKING} — the default, most
 * transient type, exactly as the "working memory first, consolidate later" model intends.
 */
public class DefaultMemoryClassifier implements MemoryClassifier {

    @Override
    public MemoryType classify(String content) {
        if (content == null || content.isBlank()) {
            return MemoryType.WORKING;
        }
        String lower = content.toLowerCase();

        if (lower.contains("decided") || lower.contains("chose") || lower.contains("choosing") || lower.contains("went with")) {
            return MemoryType.DECISION;
        }
        if (lower.contains("prefer") || lower.contains("don't want") || lower.contains("dislike") || lower.contains("would rather")) {
            return MemoryType.PREFERENCE;
        }
        if (lower.contains("learned") || lower.contains("course") || lower.contains("certification") || lower.contains("studying")) {
            return MemoryType.LEARNING;
        }
        if (lower.contains("skill") || lower.contains("proficient") || lower.contains("expert in")) {
            return MemoryType.SKILL;
        }
        if (lower.contains("career goal") || lower.contains("promotion") || lower.contains("career path")) {
            return MemoryType.CAREER;
        }
        if (lower.contains("said") || lower.contains("asked") || lower.contains("mentioned")) {
            return MemoryType.CONVERSATION;
        }
        return MemoryType.WORKING;
    }

    @Override
    public MemoryImportance scoreImportance(String content, MemoryType type) {
        if (content == null || content.isBlank()) {
            return new MemoryImportance(0.0);
        }
        String lower = content.toLowerCase();
        double score = 0.3;
        if (lower.contains("important") || lower.contains("critical") || lower.contains("urgent")) {
            score += 0.3;
        }
        if (content.length() > 100) {
            score += 0.2;
        }
        // A memory explicitly promoted out of WORKING already earned some durability.
        if (type != MemoryType.WORKING) {
            score += 0.1;
        }
        return new MemoryImportance(score);
    }
}
