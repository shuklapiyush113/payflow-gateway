package dev.payflow.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default publisher for local runs and tests without a broker. */
@Component
@ConditionalOnProperty(name = "payflow.events.publisher", havingValue = "log", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(String key, String eventType, String payload) {
        log.info("event {} key={} payload={}", eventType, key, payload);
    }
}
