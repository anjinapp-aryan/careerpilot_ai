package ai.careerpilot.workflowplanner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowValidatorTest {

    private final DefaultWorkflowValidator validator = new DefaultWorkflowValidator();

    private WorkflowStep step(int number, List<Integer> deps) {
        return new WorkflowStep(number, "s" + number, "d", null, List.of(), List.of(), 1,
                Duration.ofMinutes(1), false, deps, false, Duration.ofMinutes(1), "n" + number);
    }

    private WorkflowPlan planWith(List<WorkflowStep> sequential) {
        return new WorkflowPlan(UUID.randomUUID(), WorkflowType.RESUME, "v1", WorkflowPriority.MEDIUM,
                UUID.randomUUID(), null, null, WorkflowComplexity.LOW, Duration.ofMinutes(1), sequential, List.of(),
                List.of(), List.of(), false, RetryStrategy.standard(), FallbackStrategy.escalateToHuman(), List.of(),
                "G", "start", "end", "auto", Map.of(), null, WorkflowExecutionStrategy.SEQUENTIAL, Instant.now());
    }

    @Test
    void noStepsIsInvalid() {
        WorkflowValidationResult result = validator.validate(planWith(List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("no steps"));
    }

    @Test
    void validPlanPasses() {
        WorkflowValidationResult result = validator.validate(planWith(List.of(step(1, List.of()), step(2, List.of(1)))));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void selfDependencyIsInvalid() {
        WorkflowValidationResult result = validator.validate(planWith(List.of(step(1, List.of(1)))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("depends on itself"));
    }

    @Test
    void dependencyOnUnknownStepIsInvalid() {
        WorkflowValidationResult result = validator.validate(planWith(List.of(step(1, List.of(99)))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("unknown step"));
    }

    @Test
    void duplicateStepNumbersAreInvalid() {
        WorkflowValidationResult result = validator.validate(planWith(List.of(step(1, List.of()), step(1, List.of()))));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Duplicate"));
    }
}
