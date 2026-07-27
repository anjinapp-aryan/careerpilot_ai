package ai.careerpilot.career.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCareerRecommendationEngineTest {

    private final DefaultCareerRecommendationEngine engine = new DefaultCareerRecommendationEngine();

    private CareerAlert alert(CareerAlertSeverity severity) {
        return CareerAlert.of(UUID.randomUUID(), CareerAlertType.JOB_MATCH, severity, "msg", Map.of());
    }

    @Test
    void ranksBySeverityCriticalFirst() {
        List<CareerAlert> ranked = engine.prioritize(
                List.of(alert(CareerAlertSeverity.LOW), alert(CareerAlertSeverity.CRITICAL), alert(CareerAlertSeverity.MEDIUM)), 10);

        assertThat(ranked.get(0).severity()).isEqualTo(CareerAlertSeverity.CRITICAL);
        assertThat(ranked.get(2).severity()).isEqualTo(CareerAlertSeverity.LOW);
    }

    @Test
    void respectsLimit() {
        List<CareerAlert> alerts = List.of(alert(CareerAlertSeverity.HIGH), alert(CareerAlertSeverity.HIGH), alert(CareerAlertSeverity.HIGH));

        assertThat(engine.prioritize(alerts, 2)).hasSize(2);
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertThat(engine.prioritize(List.of(), 10)).isEmpty();
    }
}
