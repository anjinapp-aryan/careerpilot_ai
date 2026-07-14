package ai.careerpilot.story.evaluator;

import ai.careerpilot.domain.StarStory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7.15 — deterministic, explainable STAR story quality scoring, mirroring the
 * {@code CompanyScoringService} convention: no LLM call, pure rules over the persisted fields, so
 * every score can be explained by pointing at the input that produced it.
 *
 * <p>Produces 9 sub-scores (Completeness, STAR correctness, Technical depth, Business impact,
 * Leadership, Communication, Confidence, Authenticity, Evidence quality) plus an Overall quality
 * score, a confidence score, missing-section list and improvement suggestions.
 */
@Component
public class StoryQualityEvaluator {

    public record Evaluation(Map<String, Integer> breakdown, int qualityScore, int confidenceScore,
                              List<String> missingSections, List<String> improvementSuggestions) {}

    private static final int MIN_SECTION_CHARS = 40;

    public Evaluation evaluate(StarStory story) {
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        int completeness = completeness(story, missing, suggestions);
        int starCorrectness = starCorrectness(story, suggestions);
        int technicalDepth = keywordScore(combine(story.getAction(), story.getTechnologiesUsed()),
                TECH_KEYWORDS, 60);
        int businessImpact = businessImpact(story, suggestions);
        int leadership = keywordScore(combine(story.getAction(), story.getSituation()), LEADERSHIP_KEYWORDS, 50);
        int communication = keywordScore(combine(story.getAction(), story.getResult()), COMMUNICATION_KEYWORDS, 45);
        int confidence = confidence(story, suggestions);
        int authenticity = authenticity(story);
        int evidenceQuality = evidenceQuality(story, missing, suggestions);

        breakdown.put("completeness", completeness);
        breakdown.put("starCorrectness", starCorrectness);
        breakdown.put("technicalDepth", technicalDepth);
        breakdown.put("businessImpact", businessImpact);
        breakdown.put("leadership", leadership);
        breakdown.put("communication", communication);
        breakdown.put("confidence", confidence);
        breakdown.put("authenticity", authenticity);
        breakdown.put("evidenceQuality", evidenceQuality);

        int overall = (int) Math.round(
                completeness * 0.25 + starCorrectness * 0.20 + technicalDepth * 0.10
                        + businessImpact * 0.15 + leadership * 0.05 + communication * 0.10
                        + confidence * 0.05 + authenticity * 0.05 + evidenceQuality * 0.05);
        breakdown.put("overall", overall);

        if (suggestions.isEmpty()) suggestions.add("Story is well-formed; consider quantifying the result further.");

        return new Evaluation(breakdown, overall, confidence, missing, suggestions);
    }

    private int completeness(StarStory s, List<String> missing, List<String> suggestions) {
        String[] labels = {"Situation", "Task", "Action", "Result"};
        String[] values = {s.getSituation(), s.getTask(), s.getAction(), s.getResult()};
        int present = 0;
        for (int i = 0; i < values.length; i++) {
            if (hasContent(values[i])) present++;
            else missing.add(labels[i]);
        }
        if (!hasContent(s.getReflection())) missing.add("Reflection");
        if (!hasContent(s.getLessonsLearned())) missing.add("Lessons Learned");
        if (!missing.isEmpty()) suggestions.add("Fill in: " + String.join(", ", missing));
        int coreScore = (int) Math.round(present / 4.0 * 80);
        int bonus = (hasContent(s.getReflection()) ? 10 : 0) + (hasContent(s.getLessonsLearned()) ? 10 : 0);
        return Math.min(100, coreScore + bonus);
    }

    private int starCorrectness(StarStory s, List<String> suggestions) {
        int score = 0;
        if (hasContent(s.getSituation()) && s.getSituation().length() >= MIN_SECTION_CHARS) score += 25;
        else suggestions.add("Situation should give enough context (who/what/when).");
        if (hasContent(s.getTask()) && s.getTask().length() >= 20) score += 25;
        else suggestions.add("Task should state your specific responsibility clearly.");
        if (hasContent(s.getAction()) && s.getAction().length() >= MIN_SECTION_CHARS) score += 25;
        else suggestions.add("Action should describe concrete steps YOU took.");
        if (hasContent(s.getResult()) && containsDigit(s.getResult())) score += 25;
        else suggestions.add("Result should be quantified with a number or metric.");
        return score;
    }

    private int businessImpact(StarStory s, List<String> suggestions) {
        String text = combine(s.getBusinessImpact(), s.getResult());
        int score = keywordScore(text, IMPACT_KEYWORDS, 40);
        if (containsDigit(text)) score = Math.min(100, score + 25);
        else suggestions.add("Add a measurable business impact (%, $, time saved, users affected).");
        return score;
    }

    private int confidence(StarStory s, List<String> suggestions) {
        if (s.getConfidenceScore() != null) return clamp(s.getConfidenceScore());
        int score = 50;
        if (hasContent(s.getEvidence())) score += 25;
        else suggestions.add("Attach evidence (metric source, artifact, or reference) to raise confidence.");
        if (containsDigit(s.getResult())) score += 15;
        return clamp(score);
    }

    private int authenticity(StarStory s) {
        // First-person, specific-detail heuristic: penalize generic filler, reward specificity.
        String text = combine(s.getSituation(), s.getAction(), s.getResult());
        int score = 60;
        if (text.toLowerCase().matches(".*\\b(i|my|we)\\b.*")) score += 15;
        if (containsDigit(text)) score += 15;
        if (hasContent(s.getTechnologiesUsed())) score += 10;
        return clamp(score);
    }

    private int evidenceQuality(StarStory s, List<String> missing, List<String> suggestions) {
        if (!hasContent(s.getEvidence())) {
            missing.add("Evidence");
            suggestions.add("Link or describe evidence that supports this story's outcome.");
            return 30;
        }
        return s.getEvidence().length() > 80 ? 90 : 65;
    }

    private static int keywordScore(String text, List<String> keywords, int base) {
        if (text == null || text.isBlank()) return Math.max(0, base - 30);
        String lower = text.toLowerCase();
        long hits = keywords.stream().filter(lower::contains).count();
        return clamp((int) (base + hits * 8));
    }

    private static boolean hasContent(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean containsDigit(String s) {
        return s != null && s.chars().anyMatch(Character::isDigit);
    }

    private static String combine(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null) sb.append(' ').append(p);
        return sb.toString();
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static final List<String> TECH_KEYWORDS = List.of(
            "architecture", "system", "api", "database", "microservice", "cloud", "kubernetes",
            "pipeline", "scalab", "latency", "throughput", "design", "algorithm");
    private static final List<String> LEADERSHIP_KEYWORDS = List.of(
            "led", "mentored", "coordinated", "owned", "drove", "influenced", "aligned", "stakeholder");
    private static final List<String> COMMUNICATION_KEYWORDS = List.of(
            "presented", "communicated", "collaborated", "negotiated", "documented", "explained");
    private static final List<String> IMPACT_KEYWORDS = List.of(
            "revenue", "cost", "saved", "reduced", "increased", "improved", "growth", "retention", "users");
}
