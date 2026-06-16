package ai.careerpilot.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WorkflowEventProducer {

    private final KafkaTemplate<String, Object> kafka;
    private final String topic;

    public WorkflowEventProducer(KafkaTemplate<String, Object> kafka,
                                 @Value("${kafka.topics.workflow-events}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    public void publish(String key, Map<String, Object> payload) {
        kafka.send(topic, key, payload);
    }
}
