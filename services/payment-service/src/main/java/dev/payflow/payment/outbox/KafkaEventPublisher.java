package dev.payflow.payment.outbox;

import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payflow.events.publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka,
                               @Value("${payflow.events.topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public void publish(String key, String eventType, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add("event-type", eventType.getBytes());
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }
}
