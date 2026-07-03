package ai.careerpilot.service.profile;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns resume text into a validated {@link ResumeIntelligence} using the shared
 * {@link AiGatewayService} (the single AI entry point — DeepSeek → Gemini → Groq → Qwen →
 * OpenRouter failover). Never calls a provider directly. The model is asked for strict JSON;
 * the response is fence-stripped, parsed, and validated before it can reach persistence.
 *
 * Logging here is deliberately content-free: resume text is never logged.
 */
@Component
public class CandidateProfileExtractor {

    private static final String SYSTEM_PROMPT = """
            You are a precise resume-analysis engine for a career platform. Extract structured
            intelligence from the candidate's resume. Respond with a SINGLE JSON object and
            nothing else — no prose, no markdown fences. Use exactly these keys:

            {
              "yearsExperience": <integer total years of professional experience>,
              "currentRole": <string, most recent job title>,
              "seniority": <one of: "Junior","Mid","Senior","Lead","Manager","Architect","Principal","Executive">,
              "skills": [<technical and professional skills, deduplicated>],
              "targetRoles": [<realistic next roles this candidate is positioned for>],
              "domains": [<industry domains, e.g. "Finance","Retail","Healthcare">],
              "languages": [<spoken/written human languages>],
              "profileSummary": <2-3 sentence neutral professional summary>,
              "confidenceScore": <number 0.0-1.0, your confidence in this extraction>,
              "technologies": [<specific tools, languages, frameworks, platforms — distinct from soft/professional skills>],
              "certifications": [<professional certifications, e.g. "AWS Certified Solutions Architect","PMP">],
              "industries": [<vertical industries the candidate has worked in, distinct from domains>],
              "leadershipExperience": <true if the resume shows people/team management or technical leadership, else false>,
              "cloudExpertise": <true if the resume shows hands-on cloud platform experience (AWS/Azure/GCP/etc.), else false>,
              "careerGoals": [<short phrases describing the candidate's apparent next-career-step aspirations>]
            }

            Infer seniority and targetRoles from experience and scope, not just the title.
            If a field is unknown, use an empty array, false, or null. Do not invent employers or PII.
            """;

    private final AiGatewayService gateway;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxResumeChars;

    public CandidateProfileExtractor(AiGatewayService gateway,
                                     @Value("${candidate.profile.max-resume-chars:12000}") int maxResumeChars) {
        this.gateway = gateway;
        this.maxResumeChars = maxResumeChars;
    }

    /**
     * Extract and validate. Throws {@link ProfileExtractionException} when the resume is empty
     * or the model returns nothing usable — callers record the failure and leave any existing
     * profile untouched.
     */
    public ResumeIntelligence extract(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            throw new ProfileExtractionException("resume text is empty");
        }
        String prompt = "Resume:\n" + truncate(resumeText, maxResumeChars);
        String raw = gateway.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
        return parseAndValidate(raw);
    }

    /** Visible for testing — the parse/validate half, independent of the AI call. */
    ResumeIntelligence parseAndValidate(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) {
            throw new ProfileExtractionException("no JSON object in model response");
        }
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (Exception e) {
            throw new ProfileExtractionException("unparseable JSON: " + e.getMessage());
        }
        if (!n.isObject()) {
            throw new ProfileExtractionException("JSON is not an object");
        }

        ResumeIntelligence ri = new ResumeIntelligence(
                intOrNull(n.get("yearsExperience")),
                textOrNull(n.get("currentRole")),
                textOrNull(n.get("seniority")),
                stringArray(n.get("skills")),
                stringArray(n.get("targetRoles")),
                stringArray(n.get("domains")),
                stringArray(n.get("languages")),
                textOrNull(n.get("profileSummary")),
                clampConfidence(n.get("confidenceScore")),
                stringArray(n.get("technologies")),
                stringArray(n.get("certifications")),
                stringArray(n.get("industries")),
                boolOrNull(n.get("leadershipExperience")),
                boolOrNull(n.get("cloudExpertise")),
                stringArray(n.get("careerGoals")));

        // Minimum viability: a profile with no role and no skills is not a usable extraction.
        if ((ri.currentRole() == null || ri.currentRole().isBlank()) && ri.skills().isEmpty()) {
            throw new ProfileExtractionException("extraction has neither role nor skills");
        }
        return ri;
    }

    // ── JSON salvage + coercion helpers ─────────────────────────────────────────

    /** Pull the first balanced-looking JSON object out of a possibly fenced/prefixed response. */
    private static String extractJsonObject(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // Strip ```json … ``` fences if present.
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String v = node.asText("").trim();
        return v.isEmpty() ? null : v;
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asInt();
        try {
            String t = node.asText("").replaceAll("[^0-9]", "");
            return t.isEmpty() ? null : Integer.parseInt(t);
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean boolOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        String t = node.asText("").trim().toLowerCase();
        if (t.equals("true")) return Boolean.TRUE;
        if (t.equals("false")) return Boolean.FALSE;
        return null;
    }

    private static List<String> stringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(el -> {
                String v = el.asText("").trim();
                if (!v.isEmpty()) out.add(v);
            });
        }
        return out;
    }

    private static BigDecimal clampConfidence(JsonNode node) {
        if (node == null || node.isNull()) return BigDecimal.ZERO;
        double d;
        try {
            d = node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText("0"));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
        if (d < 0) d = 0;
        if (d > 1) d = 1;
        return BigDecimal.valueOf(d);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
