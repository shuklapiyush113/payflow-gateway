package dev.payflow.payment.outbox;

/**
 * Port to the message broker. Implementations must not return until the broker has accepted the
 * message; the relay marks an event published only after this returns.
 */
public interface EventPublisher {

    /** {@code key} decides the partition, so all events for one payment stay in order. */
    void publish(String key, String eventType, String payload) throws Exception;
}
