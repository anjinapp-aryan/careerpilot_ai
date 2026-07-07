package ai.careerpilot.learning.career;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6.6 — deterministic probability/demand math over a user's own learning-event history and
 * {@link SuccessPattern} rows. No LLM. Formulas (all 0..1, disclosed here rather than in a
 * separate doc):
 * <ul>
 *   <li>{@code interviewProbability} = interviews / applications (all events, any dimension)</li>
 *   <li>{@code offerProbability} = offers / applications</li>
 *   <li>{@code careerSuccessProbability} = applications-weighted average of every
 *       {@code SuccessPattern.successRate} the user has (their overall track record across
 *       companies/roles/skills/etc.)</li>
 *   <li>{@code careerGrowthProbability} = average of the above two probabilities — a rising
 *       interview+offer rate is the plainest deterministic proxy for "growth" available without an LLM</li>
 *   <li>{@code marketDemandScore} = share of distinct companies applied to that produced at least
 *       one interview or offer — a purely count-based proxy for "your profile clears initial screens
 *       broadly", not a real labor-market signal</li>
 * </ul>
 */
@Component
public class CareerProbabilityEngine {

    public record Probabilities(BigDecimal careerSuccessProbability, BigDecimal interviewProbability,
                                BigDecimal offerProbability, BigDecimal careerGrowthProbability,
                                BigDecimal marketDemandScore) {}

    private final LearningEventRepository events;
    private final SuccessPatternRepository successPatterns;

    public CareerProbabilityEngine(LearningEventRepository events, SuccessPatternRepository successPatterns) {
        this.events = events;
        this.successPatterns = successPatterns;
    }

    public Probabilities compute(UUID userId) {
        List<LearningEvent> history = events.findByUserIdOrderByCreatedAtDesc(userId);
        int applications = countType(history, LearningEventType.APPLICATION_SUBMITTED);
        int interviews = countType(history, LearningEventType.INTERVIEW_SCHEDULED);
        int offers = countType(history, LearningEventType.OFFER_RECEIVED);

        BigDecimal interviewProbability = ratio(interviews, applications);
        BigDecimal offerProbability = ratio(offers, applications);
        BigDecimal careerSuccessProbability = weightedSuccessRate(userId);
        BigDecimal careerGrowthProbability = interviewProbability.add(offerProbability)
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        BigDecimal marketDemandScore = marketDemand(history);

        return new Probabilities(careerSuccessProbability, interviewProbability, offerProbability,
                careerGrowthProbability, marketDemandScore);
    }

    private BigDecimal weightedSuccessRate(UUID userId) {
        List<SuccessPattern> patterns = successPatterns.findByUserIdOrderBySuccessRateDesc(userId);
        long totalApplications = patterns.stream().mapToLong(SuccessPattern::getApplications).sum();
        if (totalApplications == 0) return BigDecimal.ZERO;
        BigDecimal weightedSum = patterns.stream()
                .filter(p -> p.getSuccessRate() != null)
                .map(p -> p.getSuccessRate().multiply(BigDecimal.valueOf(p.getApplications())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return weightedSum.divide(BigDecimal.valueOf(totalApplications), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal marketDemand(List<LearningEvent> history) {
        var companiesApplied = history.stream()
                .filter(e -> LearningEventType.APPLICATION_SUBMITTED.name().equals(e.getEventType()))
                .map(LearningEvent::getCompany).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (companiesApplied.isEmpty()) return BigDecimal.ZERO;
        var companiesResponded = history.stream()
                .filter(e -> LearningEventType.INTERVIEW_SCHEDULED.name().equals(e.getEventType())
                        || LearningEventType.OFFER_RECEIVED.name().equals(e.getEventType()))
                .map(LearningEvent::getCompany).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        long matched = companiesApplied.stream().filter(companiesResponded::contains).count();
        return ratio((int) matched, companiesApplied.size());
    }

    private static int countType(List<LearningEvent> events, LearningEventType type) {
        return (int) events.stream().filter(e -> type.name().equals(e.getEventType())).count();
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
