package ai.careerpilot.intent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 11.1 — the default {@link IntentRegistry}, pre-populated with the seven intents named
 * or exemplified in the phase spec. Keywords are chosen to cover the spec's own worked examples
 * ("I keep getting rejected" → Resume Analysis, "Can I crack Amazon?" → Interview Preparation,
 * etc.) — see {@link KeywordIntentResolver} for how they're actually matched.
 */
public class InMemoryIntentRegistry implements IntentRegistry {

    private final Map<IntentType, IntentDefinition> definitions;

    public InMemoryIntentRegistry() {
        this.definitions = Map.ofEntries(
                Map.entry(IntentType.GITHUB_ANALYSIS, new IntentDefinition(
                        IntentType.GITHUB_ANALYSIS, "Review a GitHub profile/portfolio", 100,
                        Set.of("github", "repo", "repository", "portfolio"))),
                Map.entry(IntentType.RESUME_ANALYSIS, new IntentDefinition(
                        IntentType.RESUME_ANALYSIS, "Analyze or improve a resume", 90,
                        Set.of("resume", "cv", "rejected", "rejection", "not getting interviews"))),
                Map.entry(IntentType.INTERVIEW_PREPARATION, new IntentDefinition(
                        IntentType.INTERVIEW_PREPARATION, "Prepare for an interview", 90,
                        Set.of("interview", "crack", "mock interview", "technical round"))),
                Map.entry(IntentType.CAREER_STRATEGY, new IntentDefinition(
                        IntentType.CAREER_STRATEGY, "Career strategy, salary, growth planning", 80,
                        Set.of("salary", "raise", "compensation", "career strategy", "career plan", "career path"))),
                Map.entry(IntentType.JOB_RECOMMENDATION, new IntentDefinition(
                        IntentType.JOB_RECOMMENDATION, "Recommend jobs", 80,
                        Set.of("recommend a job", "job recommend", "job match"))),
                Map.entry(IntentType.EXECUTIVE_COACH, new IntentDefinition(
                        IntentType.EXECUTIVE_COACH, "General career confusion/direction coaching", 70,
                        Set.of("confused about my career", "lost in my career", "don't know what to do",
                                "career direction", "feeling stuck"))),
                Map.entry(IntentType.LEARNING_HELP, new IntentDefinition(
                        IntentType.LEARNING_HELP, "Framework/API documentation questions", 60,
                        Set.of("documentation", "how do i use", "explain", "spring ai", "spring boot", "langgraph"))));
    }

    @Override
    public Optional<IntentDefinition> find(IntentType type) {
        return Optional.ofNullable(definitions.get(type));
    }

    @Override
    public List<IntentDefinition> all() {
        return List.copyOf(definitions.values());
    }
}
