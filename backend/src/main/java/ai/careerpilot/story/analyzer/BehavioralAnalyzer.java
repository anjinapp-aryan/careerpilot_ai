package ai.careerpilot.story.analyzer;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.story.BehavioralCompetency;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 7.15 — deterministic, keyword-based classification of a story against the 18
 * {@link BehavioralCompetency} values. Prefers explainable rule-based scoring over another LLM
 * call, per the CLAUDE.md guidance to mirror {@code CompanyScoringService}'s style.
 */
@Component
public class BehavioralAnalyzer {

    private static final Map<BehavioralCompetency, List<String>> KEYWORDS = new EnumMap<>(BehavioralCompetency.class);
    static {
        KEYWORDS.put(BehavioralCompetency.LEADERSHIP, List.of("led", "leadership", "directed", "managed", "owned"));
        KEYWORDS.put(BehavioralCompetency.OWNERSHIP, List.of("owned", "accountable", "responsible", "drove"));
        KEYWORDS.put(BehavioralCompetency.CUSTOMER_FOCUS, List.of("customer", "user", "client", "satisfaction"));
        KEYWORDS.put(BehavioralCompetency.INNOVATION, List.of("innovat", "novel", "new approach", "invented", "prototype"));
        KEYWORDS.put(BehavioralCompetency.EXECUTION, List.of("delivered", "shipped", "launched", "completed", "deadline"));
        KEYWORDS.put(BehavioralCompetency.COMMUNICATION, List.of("presented", "communicated", "explained", "documented"));
        KEYWORDS.put(BehavioralCompetency.PROBLEM_SOLVING, List.of("root cause", "solved", "diagnosed", "debugged", "resolved"));
        KEYWORDS.put(BehavioralCompetency.DECISION_MAKING, List.of("decided", "trade-off", "tradeoff", "chose", "prioritized"));
        KEYWORDS.put(BehavioralCompetency.LEARNING, List.of("learned", "researched", "studied", "upskilled"));
        KEYWORDS.put(BehavioralCompetency.ADAPTABILITY, List.of("adapted", "pivoted", "flexible", "changing requirements"));
        KEYWORDS.put(BehavioralCompetency.TECHNICAL_DEPTH, List.of("algorithm", "performance", "optimi", "complexity", "code"));
        KEYWORDS.put(BehavioralCompetency.ARCHITECTURE, List.of("architecture", "design pattern", "system design", "blueprint"));
        KEYWORDS.put(BehavioralCompetency.SYSTEM_DESIGN, List.of("scalab", "distributed", "throughput", "latency", "system design"));
        KEYWORDS.put(BehavioralCompetency.CLOUD, List.of("aws", "azure", "gcp", "cloud", "kubernetes", "docker"));
        KEYWORDS.put(BehavioralCompetency.SECURITY, List.of("security", "vulnerab", "encrypt", "compliance", "audit"));
        KEYWORDS.put(BehavioralCompetency.CODING, List.of("code", "implementation", "wrote", "developed", "refactor"));
        KEYWORDS.put(BehavioralCompetency.MENTORSHIP, List.of("mentored", "coached", "trained", "onboarded"));
        KEYWORDS.put(BehavioralCompetency.BUSINESS_THINKING, List.of("revenue", "cost", "roi", "business", "stakeholder"));
    }

    /** Score every competency 0-100 for the given story's text; only positive-hit competencies are meaningful. */
    public Map<BehavioralCompetency, Integer> classify(StarStory story) {
        String text = lower(story.getSituation(), story.getTask(), story.getAction(),
                story.getResult(), story.getReflection(), story.getBusinessImpact());
        Map<BehavioralCompetency, Integer> scores = new EnumMap<>(BehavioralCompetency.class);
        for (Map.Entry<BehavioralCompetency, List<String>> e : KEYWORDS.entrySet()) {
            long hits = e.getValue().stream().filter(text::contains).count();
            int score = hits == 0 ? 0 : Math.min(100, 40 + (int) hits * 20);
            scores.put(e.getKey(), score);
        }
        return scores;
    }

    /** Top N competencies by score, excluding zero-hit ones. */
    public List<BehavioralCompetency> topCompetencies(StarStory story, int n) {
        return classify(story).entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<BehavioralCompetency, Integer>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String lower(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null) sb.append(' ').append(p.toLowerCase());
        return sb.toString();
    }
}
