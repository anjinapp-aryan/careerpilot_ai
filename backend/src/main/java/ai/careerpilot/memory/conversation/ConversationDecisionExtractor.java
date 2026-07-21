package ai.careerpilot.memory.conversation;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 7.15.2 — turns one Copilot user message into zero or more {@link ExtractedDecision}s,
 * using the shared {@link AiGatewayService} (never a provider directly) — same prompt/parse
 * shape as {@code CandidateProfileService}'s {@code CandidateProfileExtractor}: strict-JSON
 * instruction, fence-stripped, defensively coerced, never throws on malformed output (returns
 * an empty list instead — "no decision found" and "extraction failed" are handled identically
 * by the caller, since a wrong guess is worse than no memory at all).
 *
 * <p>The prompt is the primary defense against remembering casual chat; the caller's confidence
 * threshold is the second, independent one — this class only knows how to ask and parse, it
 * does not decide what gets written.
 */
@Component
public class ConversationDecisionExtractor {

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "CAREER", "TECHNOLOGY", "COUNTRY", "SALARY", "COMPANY", "INDUSTRY", "LEARNING",
            "INTERVIEW", "OFFER", "APPLICATION", "PREFERENCE", "BEHAVIOR", "NETWORKING",
            "WORK_MODE", "COMPANY_SIZE");
    private static final Set<String> VALID_POLARITIES = Set.of("POSITIVE", "NEGATIVE", "NEUTRAL");
    private static final Set<String> VALID_PERMANENCE = Set.of("TEMPORARY", "PERMANENT");

    private static final String SYSTEM_PROMPT = """
            You are a precise career-decision detector for a career platform's AI Copilot. You read
            ONE user chat message and decide whether it contains a MEANINGFUL, LONG-TERM career
            decision or preference worth remembering permanently — not ordinary conversation.

            Respond with a SINGLE JSON array and nothing else — no prose, no markdown fences. Each
            element must have exactly these keys:

            {
              "category": <one of: CAREER, TECHNOLOGY, COUNTRY, SALARY, COMPANY, INDUSTRY, LEARNING, INTERVIEW, OFFER, APPLICATION, PREFERENCE, BEHAVIOR, NETWORKING, WORK_MODE, COMPANY_SIZE>,
              "value": <short string — the specific thing decided, e.g. "Germany", "Kubernetes", "Frontend roles">,
              "polarity": <one of: POSITIVE, NEGATIVE, NEUTRAL>,
              "permanence": <one of: TEMPORARY, PERMANENT>,
              "reason": <short string, the user's own stated reason, or null if none was given>,
              "sourceSentence": <the exact sentence or clause the decision came from>,
              "confidence": <number 0.0-1.0, how certain you are this is a real, meaningful, durable decision>
            }

            Return an EMPTY ARRAY ([]) for: greetings, thanks, small talk, general programming
            questions, requests to explain something, or anything that is not a durable statement
            about the candidate's own career goals/preferences/decisions.

            Examples that SHOULD produce an entry:
            "I no longer want frontend jobs" -> TECHNOLOGY, "Frontend", NEGATIVE, PERMANENT
            "I want Germany only" -> COUNTRY, "Germany", POSITIVE, PERMANENT
            "My minimum salary is 120K EUR" -> SALARY, "120000 EUR minimum", POSITIVE, PERMANENT
            "I'm learning Kubernetes" -> LEARNING, "Kubernetes", POSITIVE, TEMPORARY
            "I failed the system design interview" -> INTERVIEW, "System design", NEGATIVE, TEMPORARY

            Examples that should produce an EMPTY array:
            "Hello", "Thanks!", "Good morning", "How are you", "Tell me a joke",
            "What's the difference between REST and GraphQL", "Can you explain this error"

            Only ever extract from what the user actually wrote — never invent a decision, employer,
            or number that was not stated.
            """;

    private final AiGatewayService gateway;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxMessageChars;

    public ConversationDecisionExtractor(AiGatewayService gateway,
                                         @Value("${career.memory.conversation.max-message-chars:2000}") int maxMessageChars) {
        this.gateway = gateway;
        this.maxMessageChars = maxMessageChars;
    }

    /** Extract and validate. Never throws — a malformed/empty response is just an empty list. */
    public List<ExtractedDecision> extract(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return List.of();
        String prompt = "User message:\n" + truncate(userMessage.strip(), maxMessageChars);
        String raw = gateway.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT);
        return parse(raw);
    }

    /** Visible for testing — the parse half, independent of the AI call. */
    List<ExtractedDecision> parse(String raw) {
        String json = extractJsonArray(raw);
        if (json == null) return List.of();
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (Exception e) {
            return List.of();
        }
        if (!n.isArray()) return List.of();

        List<ExtractedDecision> out = new ArrayList<>();
        for (JsonNode el : n) {
            ExtractedDecision d = toDecision(el);
            if (d != null) out.add(d);
        }
        return out;
    }

    private ExtractedDecision toDecision(JsonNode el) {
        if (el == null || !el.isObject()) return null;
        String category = textOrNull(el.get("category"));
        String value = textOrNull(el.get("value"));
        if (category == null || value == null) return null;
        category = category.toUpperCase();
        if (!VALID_CATEGORIES.contains(category)) return null;

        String polarity = textOrNull(el.get("polarity"));
        polarity = polarity != null && VALID_POLARITIES.contains(polarity.toUpperCase()) ? polarity.toUpperCase() : "NEUTRAL";
        String permanence = textOrNull(el.get("permanence"));
        permanence = permanence != null && VALID_PERMANENCE.contains(permanence.toUpperCase()) ? permanence.toUpperCase() : "TEMPORARY";

        return new ExtractedDecision(category, value, polarity, permanence,
                textOrNull(el.get("reason")), textOrNull(el.get("sourceSentence")),
                clampConfidence(el.get("confidence")));
    }

    // ── JSON salvage + coercion helpers — same approach as CandidateProfileExtractor ──────

    private static String extractJsonArray(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String v = node.asText("").trim();
        return v.isEmpty() ? null : v;
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

    /** One candidate decision straight from the model — not yet threshold-checked or deduped. */
    public record ExtractedDecision(String category, String value, String polarity, String permanence,
                                    String reason, String sourceSentence, BigDecimal confidence) {}
}
