package dev.payflow.payment.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes events to the outbox table in the same transaction as the business change. Either both
 * commit or neither does, so an event is never lost and never sent for a rolled-back change.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public OutboxWriter(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(PaymentEvent event) {
        outbox.save(new OutboxEvent(event.eventId(), event.paymentId().toString(), event.type(),
                json.writeValueAsString(event), event.occurredAt()));
    }
}
