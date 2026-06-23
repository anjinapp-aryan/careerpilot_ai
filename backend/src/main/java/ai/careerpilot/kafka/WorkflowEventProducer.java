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

    /**
     * Publish a resume-optimization event (e.g. {@code resume.optimization.completed},
     * {@code resume.version.created}) onto the same best-effort workflow topic, tagged with
     * an {@code event} type. Stage 1 has no dedicated topic/consumers — this is fire-and-forget.
     */
    public void publishResumeEvent(String key, String eventType, Map<String, Object> payload) {
        Map<String, Object> enriched = new java.util.HashMap<>(payload == null ? Map.of() : payload);
        enriched.put("event", eventType);
        publish(key, enriched);
    }

    /**
     * Publish a job-discovery event (e.g. {@code job.discovery.completed}) onto the same
     * best-effort workflow topic, tagged with an {@code event} type. Phase 2 has no
     * dedicated topic/consumers — fire-and-forget, never breaks ingest.
     */
    public void publishJobEvent(String key, String eventType, Map<String, Object> payload) {
        Map<String, Object> enriched = new java.util.HashMap<>(payload == null ? Map.of() : payload);
        enriched.put("event", eventType);
        publish(key, enriched);
    }
}
