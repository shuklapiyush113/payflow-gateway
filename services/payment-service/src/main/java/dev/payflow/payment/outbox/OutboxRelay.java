package dev.payflow.payment.outbox;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox and hands events to the broker. Delivery is at-least-once: a crash after the
 * broker accepts an event but before the commit sends it again, which is why consumers dedupe on
 * the event id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, Clock clock,
                       @Value("${payflow.outbox.batch-size:100}") int batchSize,
                       @Value("${payflow.outbox.max-attempts:10}") int maxAttempts) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval:1s}")
    @Transactional
    public int relayBatch() {
        List<OutboxEvent> batch = outbox.lockUnpublished(batchSize, maxAttempts);
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event.getAggregateId(), event.getEventType(), event.getPayload());
                event.markPublished(clock.instant());
                published++;
            } catch (Exception e) {
                event.markFailed(e.getMessage());
                log.warn("outbox event {} failed (attempt {}): {}", event.getId(), event.getAttempts(), e.getMessage());
                // Stop the batch so later events for the same payment are not published out of order.
                break;
            }
        }
        return published;
    }
}
