package ai.careerpilot.missionexecution;

import java.util.Map;

/**
 * Pre-Phase-9 Hardening — a named, evaluable gate a workflow must satisfy before it can execute
 * (e.g. "Resume Score >= 90"). {@code metricKey} is looked up in {@link
 * ExecutionContext#metrics()}, a caller-supplied bag — this package never computes a resume
 * score, LinkedIn completeness, etc. itself (that would be business logic this layer must not
 * own); it only compares numbers the caller already provides. A missing metric is treated as
 * "not met" (fail-safe: unknown is never assumed satisfied).
 */
public record Precondition(String description, String metricKey, PreconditionOperator operator, double threshold) {

    public boolean isMet(Map<String, Double> metrics) {
        Double value = metrics.get(metricKey);
        if (value == null) {
            return false;
        }
        return switch (operator) {
            case GTE -> value >= threshold;
            case LTE -> value <= threshold;
            case GT -> value > threshold;
            case LT -> value < threshold;
            case EQ -> value.doubleValue() == threshold;
            case IS_TRUE -> value != 0.0;
        };
    }
}
