package ai.careerpilot.story.engine;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryVersion;
import ai.careerpilot.repo.StoryVersionRepository;
import ai.careerpilot.story.StorySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.15 — owns the immutable revision history of a {@link StarStory}, mirroring
 * {@code KnowledgeVersionManager}. Every update appends a {@link StoryVersion} snapshot (JSON dump
 * of the full STAR field set); rollback restores an older snapshot as a NEW head version.
 */
@Component
public class StoryVersionManager {

    private final StoryVersionRepository versions;
    private final ObjectMapper mapper = new ObjectMapper();

    public StoryVersionManager(StoryVersionRepository versions) {
        this.versions = versions;
    }

    public StoryVersion snapshot(StarStory story, String changeSummary, StorySource source) {
        return versions.save(StoryVersion.builder()
                .starStoryId(story.getId())
                .userId(story.getUserId())
                .version(story.getCurrentVersion())
                .snapshot(toJson(story))
                .changeSummary(changeSummary)
                .source(source == null ? null : source.name())
                .build());
    }

    public List<StoryVersion> history(UUID starStoryId) {
        return versions.findByStarStoryIdOrderByVersionDesc(starStoryId);
    }

    public Optional<StoryVersion> version(UUID starStoryId, int version) {
        return versions.findByStarStoryIdAndVersion(starStoryId, version);
    }

    /** Apply a restored snapshot's fields onto the (already-fetched) head entity; caller persists it. */
    public boolean applySnapshot(StarStory head, StoryVersion snapshot) {
        Map<String, Object> fields = fromJson(snapshot.getSnapshot());
        if (fields.isEmpty()) return false;
        head.setSituation((String) fields.get("situation"));
        head.setTask((String) fields.get("task"));
        head.setAction((String) fields.get("action"));
        head.setResult((String) fields.get("result"));
        head.setReflection((String) fields.get("reflection"));
        head.setLessonsLearned((String) fields.get("lessonsLearned"));
        head.setSkillsUsed((String) fields.get("skillsUsed"));
        head.setTechnologiesUsed((String) fields.get("technologiesUsed"));
        head.setCompetencies((String) fields.get("competencies"));
        head.setBusinessImpact((String) fields.get("businessImpact"));
        head.setEvidence((String) fields.get("evidence"));
        return true;
    }

    private String toJson(StarStory s) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("situation", s.getSituation());
        fields.put("task", s.getTask());
        fields.put("action", s.getAction());
        fields.put("result", s.getResult());
        fields.put("reflection", s.getReflection());
        fields.put("lessonsLearned", s.getLessonsLearned());
        fields.put("skillsUsed", s.getSkillsUsed());
        fields.put("technologiesUsed", s.getTechnologiesUsed());
        fields.put("competencies", s.getCompetencies());
        fields.put("businessImpact", s.getBusinessImpact());
        fields.put("evidence", s.getEvidence());
        try {
            return mapper.writeValueAsString(fields);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
