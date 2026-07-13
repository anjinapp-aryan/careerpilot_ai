package ai.careerpilot.story;

/**
 * Phase 7.15 — the 18 behavioral competencies a story can be scored against. Classification is
 * deterministic keyword-based scoring in {@link ai.careerpilot.story.analyzer.BehavioralAnalyzer},
 * mirroring the "10 deterministic explainable scores" convention from Company Intelligence
 * ({@code CompanyScoringService}).
 */
public enum BehavioralCompetency {
    LEADERSHIP,
    OWNERSHIP,
    CUSTOMER_FOCUS,
    INNOVATION,
    EXECUTION,
    COMMUNICATION,
    PROBLEM_SOLVING,
    DECISION_MAKING,
    LEARNING,
    ADAPTABILITY,
    TECHNICAL_DEPTH,
    ARCHITECTURE,
    SYSTEM_DESIGN,
    CLOUD,
    SECURITY,
    CODING,
    MENTORSHIP,
    BUSINESS_THINKING
}
