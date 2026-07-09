package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.learning.LearningEventType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Phase 6.2/6.3 — the deterministic math shared by every success/failure dimension analyzer. No LLM involved. */
public final class PatternStatsCalculator {

    private PatternStatsCalculator() {}

    public record SuccessStats(int applications, int interviews, int offers, BigDecimal successRate) {}

    public record FailureStats(int applications, int responses, BigDecimal failureRate, Integer recommendedPenalty) {}

    public static SuccessStats success(List<LearningEvent> matching) {
        int applications = countType(matching, LearningEventType.APPLICATION_SUBMITTED);
        int interviews = countType(matching, LearningEventType.INTERVIEW_SCHEDULED);
        int offers = countType(matching, LearningEventType.OFFER_RECEIVED);
        BigDecimal rate = applications == 0 ? null : ratio(offers, applications);
        return new SuccessStats(applications, interviews, offers, rate);
    }

    /** "Response" = any signal the employer engaged at all (interview, offer, or an explicit rejection). */
    public static FailureStats failure(List<LearningEvent> matching) {
        int applications = countType(matching, LearningEventType.APPLICATION_SUBMITTED);
        int responses = countAny(matching, LearningEventType.INTERVIEW_SCHEDULED,
                LearningEventType.OFFER_RECEIVED, LearningEventType.APPLICATION_REJECTED);
        if (applications == 0) return new FailureStats(0, responses, null, null);
        BigDecimal responseRate = ratio(responses, applications);
        BigDecimal failureRate = BigDecimal.ONE.subtract(responseRate);
        int penalty = -Math.min(30, Math.round(failureRate.floatValue() * 30));
        return new FailureStats(applications, responses, failureRate, penalty);
    }

    private static int countType(List<LearningEvent> events, LearningEventType type) {
        return (int) events.stream().filter(e -> type.name().equals(e.getEventType())).count();
    }

    private static int countAny(List<LearningEvent> events, LearningEventType... types) {
        java.util.Set<String> names = java.util.Arrays.stream(types).map(Enum::name).collect(java.util.stream.Collectors.toSet());
        return (int) events.stream().filter(e -> names.contains(e.getEventType())).count();
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
