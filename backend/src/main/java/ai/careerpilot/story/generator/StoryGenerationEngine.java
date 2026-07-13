package ai.careerpilot.story.generator;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.extractor.StoryExtractionEngine.RawMaterial;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 7.15 — generates a full STAR narrative via {@link AiGatewayService} (the single AI entry
 * point; never call a provider directly), mirroring how resume-tailoring generation prompts the
 * gateway for JSON-shaped output with {@code chat()} and parses the response. Falls back to a
 * deterministic skeleton draft if the provider call fails or returns unparsable output, so
 * generation never throws to the caller.
 */
@Component
public class StoryGenerationEngine {

    private static final Logger log = LoggerFactory.getLogger(StoryGenerationEngine.class);

    private final AiGatewayService aiGateway;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public StoryGenerationEngine(AiGatewayService aiGateway,
                                 @Value("${story.generation.enabled:false}") boolean enabled) {
        this.aiGateway = aiGateway;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    public record Draft(String situation, String task, String action, String result, String reflection,
                        String lessonsLearned, String skillsUsed, String technologiesUsed, String businessImpact) {
        static Draft empty(StoryType type) {
            String t = type == null ? "this achievement" : type.name().toLowerCase().replace('_', ' ');
            return new Draft(
                    "Describe the situation around " + t + ".", "Describe your specific responsibility.",
                    "Describe the concrete actions you took.", "Describe the measurable result.",
                    "", "", "", "", "");
        }
    }

    public Draft generate(StoryType storyType, RawMaterial material, String hint) {
        if (!enabled) return Draft.empty(storyType);
        try {
            String system = """
                You are a career coach generating a STAR (Situation, Task, Action, Result) behavioral
                interview story. Respond ONLY with a single minified JSON object with exactly these
                string keys: situation, task, action, result, reflection, lessonsLearned, skillsUsed,
                technologiesUsed, businessImpact. Keep each value concise (2-4 sentences), specific,
                and first-person. skillsUsed and technologiesUsed are comma-separated lists.
                """;
            StringBuilder user = new StringBuilder();
            user.append("Story type: ").append(storyType == null ? "GENERAL" : storyType.name()).append("\n");
            if (hint != null && !hint.isBlank()) user.append("Guidance: ").append(hint).append("\n");
            if (material != null) {
                if (material.resumeText() != null) {
                    user.append("Resume excerpt: ").append(truncate(material.resumeText(), 3000)).append("\n");
                }
                if (material.resumeSkills() != null) {
                    user.append("Known skills: ").append(truncate(material.resumeSkills(), 800)).append("\n");
                }
                if (!material.applicationHighlights().isEmpty()) {
                    user.append("Application history: ").append(String.join("; ", material.applicationHighlights())).append("\n");
                }
                if (!material.companyContext().isEmpty()) {
                    user.append("Company context: ").append(String.join("; ", material.companyContext())).append("\n");
                }
            }
            user.append("Generate the JSON now.");

            String raw = aiGateway.chat(List.of(ChatMessage.user(user.toString())), system);
            return parse(raw, storyType);
        } catch (Exception e) {
            log.warn("STORY_INTEL generation failed, using fallback skeleton: {}", e.toString());
            return Draft.empty(storyType);
        }
    }

    private Draft parse(String raw, StoryType storyType) {
        try {
            String json = extractJson(raw);
            JsonNode node = mapper.readTree(json);
            return new Draft(
                    text(node, "situation"), text(node, "task"), text(node, "action"), text(node, "result"),
                    text(node, "reflection"), text(node, "lessonsLearned"), text(node, "skillsUsed"),
                    text(node, "technologiesUsed"), text(node, "businessImpact"));
        } catch (Exception e) {
            log.warn("STORY_INTEL could not parse generation output, using fallback: {}", e.toString());
            return Draft.empty(storyType);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String extractJson(String raw) {
        if (raw == null) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : "{}";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
