package ai.careerpilot.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes workflow state-change events to Kafka.
 *
 * <p>Kafka is treated as a best-effort side channel: it must never break the
 * workflow path. Publishing is skipped entirely when {@code careerpilot.kafka.enabled=false},
 * and any broker failure (unreachable host, serialization error, async send
 * rejection) is caught and logged rather than propagated to the caller.
 */
@Component
public class WorkflowEventProducer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventProducer.class);

    private final KafkaTemplate<String, Object> kafka;
    private final String topic;
    private final boolean enabled;

    public WorkflowEventProducer(KafkaTemplate<String, Object> kafka,
                                 @Value("${kafka.topics.workflow-events}") String topic,
                                 @Value("${careerpilot.kafka.enabled:true}") boolean enabled) {
        this.kafka = kafka;
        this.topic = topic;
        this.enabled = enabled;
    }

    public void publish(String key, Map<String, Object> payload) {
        if (!enabled) {
            log.debug("Kafka disabled; skipping workflow event publish for key={}", key);
            return;
        }
        try {
            kafka.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Workflow event publish failed (async) for key={}: {}", key, ex.toString());
                }
            });
        } catch (Exception e) {
            // Synchronous failures (broker DNS unresolved, serialization, etc.)
            // must not break the workflow transaction.
            log.warn("Workflow event publish failed (sync) for key={}: {}", key, e.toString());
        }
    }
}
