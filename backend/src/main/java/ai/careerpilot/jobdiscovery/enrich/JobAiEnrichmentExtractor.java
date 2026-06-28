package ai.careerpilot.jobdiscovery.enrich;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.domain.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a discovered {@link Job} into a validated {@link JobEnrichmentResult} via the shared
 * {@link AiGatewayService} (the single AI entry point, with DeepSeek → Gemini → Groq → Qwen →
 * OpenRouter failover). Never calls a provider directly. Extracts only the *semantic* signals the
 * cheap keyword tier ({@code JobEnricher}) cannot derive — normalized seniority, canonical skills,
 * industry domains, an estimated salary band, and a short summary.
 *
 * <p>The model is asked for strict JSON; the response is fence-stripped, parsed, and validated
 * before it can reach persistence — mirroring {@code CandidateProfileExtractor}. Logging is
 * content-free (job titles/descriptions are never logged here).
 */
@Component
public class JobAiEnrichmentExtractor {

    private static final String SYSTEM_PROMPT = """
            You are a precise job-posting analysis engine for a career platform. Extract structured
            intelligence from a single job posting. Respond with a SINGLE JSON object and nothing
            else — no prose, no markdown fences. Use exactly these keys:

            {
              "seniorityLevel": <one of: "Junior","Mid","Senior","Lead","Staff","Principal","Manager","Director">,
              "normalizedSkills": [<canonical technical/professional skill names, deduplicated, e.g. "React","Kubernetes","PostgreSQL">],
              "domains": [<industry domains, e.g. "Fintech","Healthcare","E-commerce">],
              "employmentType": <one of: "Full-time","Part-time","Contract","Internship","Temporary">,
              "salaryBandMin": <integer annual minimum in the stated or inferred currency, or null>,
              "salaryBandMax": <integer annual maximum, or null>,
              "salaryCurrency": <ISO currency code, e.g. "USD","EUR","INR", or null>,
              "salaryEstimated": <true if you inferred the band from market norms; false if the posting stated it>,
              "summary": <1-2 sentence neutral summary of the role>,
              "confidenceScore": <number 0.0-1.0, your confidence in this extraction>
            }

            Infer seniority from responsibilities and required experience, not just the title.
            If the posting states a salary, use it and set salaryEstimated=false. If it does not,
            estimate a realistic market band for the role + location and set salaryEstimated=true.
            If a field is genuinely unknown, use null or an empty array. Do not invent the employer.
            """;

    private final AiGatewayService gateway;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxChars;
    private final List<String> preferredProviders;

    public JobAiEnrichmentExtractor(AiGatewayService gateway,
                                    @Value("${jobs.enrich.ai.max-chars:6000}") int maxChars,
                                    @Value("${jobs.enrich.ai.providers:gemini}") List<String> preferredProviders) {
        this.gateway = gateway;
        this.maxChars = maxChars;
        this.preferredProviders = preferredProviders;
    }

    /**
     * Extract and validate enrichment for a job. Throws {@link JobEnrichmentException} when the job
     * has no usable text or the model returns nothing usable — callers record the failure and leave
     * the job un-enriched.
     */
    public JobEnrichmentResult extract(Job job) {
        String prompt = buildPrompt(job);
        if (prompt.isBlank()) {
            throw new JobEnrichmentException("job has no title/description to enrich");
        }
        // Prefer a fast model for this structured-extraction task (default: gemini flash) — a slow
        // reasoning model spends ~minute/job. Still falls back to the rest of the chain on failure.
        String raw = gateway.chat(List.of(ChatMessage.user(prompt)), SYSTEM_PROMPT, preferredProviders);
        return parseAndValidate(raw);
    }

    /** Compact posting text for the model: title, company, location, known salary, then description. */
    private String buildPrompt(Job job) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "Title", job.getTitle());
        appendLine(sb, "Company", job.getCompany());
        appendLine(sb, "Location", job.getLocation());
        appendLine(sb, "Country", job.getCountry());
        // Hand the model any salary the posting already carries so it can pass it through
        // (salaryEstimated=false) rather than re-estimating.
        if (job.getSalaryRange() != null && !job.getSalaryRange().isBlank()) {
            appendLine(sb, "Stated salary", job.getSalaryRange());
        } else if (job.getSalaryMin() != null || job.getSalaryMax() != null) {
            appendLine(sb, "Stated salary",
                    nz(job.getSalaryMin()) + "-" + nz(job.getSalaryMax()) + " " + nz(job.getCurrency()));
        }
        if (job.getDescription() != null && !job.getDescription().isBlank()) {
            appendLine(sb, "Description", truncate(job.getDescription(), maxChars));
        }
        return sb.toString().trim();
    }

    /** Visible for testing — the parse/validate half, independent of the AI call. */
    JobEnrichmentResult parseAndValidate(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) {
            throw new JobEnrichmentException("no JSON object in model response");
        }
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (Exception e) {
            throw new JobEnrichmentException("unparseable JSON: " + e.getMessage());
        }
        if (!n.isObject()) {
            throw new JobEnrichmentException("JSON is not an object");
        }

        JobEnrichmentResult r = new JobEnrichmentResult(
                textOrNull(n.get("seniorityLevel")),
                stringArray(n.get("normalizedSkills")),
                stringArray(n.get("domains")),
                textOrNull(n.get("employmentType")),
                decimalOrNull(n.get("salaryBandMin")),
                decimalOrNull(n.get("salaryBandMax")),
                textOrNull(n.get("salaryCurrency")),
                boolOrNull(n.get("salaryEstimated")),
                textOrNull(n.get("summary")),
                clampConfidence(n.get("confidenceScore")));

        // Minimum viability: an enrichment with no seniority and no skills is not usable.
        if ((r.seniorityLevel() == null || r.seniorityLevel().isBlank()) && r.normalizedSkills().isEmpty()) {
            throw new JobEnrichmentException("enrichment has neither seniority nor skills");
        }
        return r;
    }

    // ── JSON salvage + coercion helpers (mirrors CandidateProfileExtractor) ──────────

    private static String extractJsonObject(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
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

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            if (node.isNumber()) return node.decimalValue();
            String t = node.asText("").replaceAll("[^0-9.]", "");
            return t.isEmpty() ? null : new BigDecimal(t);
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

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private static String nz(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
