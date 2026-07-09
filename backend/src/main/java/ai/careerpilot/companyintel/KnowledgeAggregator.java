package ai.careerpilot.companyintel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7.13 — pure, deterministic merge of knowledge section maps. Rules: a blank update never
 * erases existing knowledge; identical content is a no-op; new content replaces the section (the
 * old value survives in the version snapshot, so nothing is ever lost). Returns exactly which
 * sections changed so the version row can record them.
 */
@Component
public class KnowledgeAggregator {

    private static final int MAX_SECTION_CHARS = 8000;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Result of one merge: the new section map plus the section keys that actually changed. */
    public record MergeResult(Map<String, String> merged, List<String> changedSections) {
        public boolean changed() { return !changedSections.isEmpty(); }
    }

    public MergeResult merge(String existingJson, Map<KnowledgeSection, String> updates) {
        Map<String, String> merged = parse(existingJson);
        List<String> changed = new java.util.ArrayList<>();
        if (updates != null) {
            for (Map.Entry<KnowledgeSection, String> e : updates.entrySet()) {
                String key = e.getKey().key();
                String value = e.getValue() == null ? "" : e.getValue().strip();
                if (value.isEmpty()) continue;                       // blank never erases knowledge
                if (value.length() > MAX_SECTION_CHARS) value = value.substring(0, MAX_SECTION_CHARS);
                if (value.equals(merged.get(key))) continue;         // identical content is a no-op
                merged.put(key, value);
                changed.add(key);
            }
        }
        return new MergeResult(merged, changed);
    }

    public Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    public String write(Map<String, String> sections) {
        try {
            return mapper.writeValueAsString(sections == null ? Map.of() : sections);
        } catch (Exception e) {
            return "{}";
        }
    }
}
