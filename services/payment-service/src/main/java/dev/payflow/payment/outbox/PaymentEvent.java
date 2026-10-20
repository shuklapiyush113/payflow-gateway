package dev.payflow.payment.outbox;

import java.time.Instant;
import java.util.UUID;

import dev.payflow.payment.domain.Payment;

/** Integration event published for every payment status change. Consumers dedupe on {@code eventId}. */
public record PaymentEvent(UUID eventId, String type, UUID paymentId, String merchantId, String status,
                           long amountMinor, long refundedMinor, String currency, Instant occurredAt) {

    public static PaymentEvent of(String type, Payment payment, Instant occurredAt) {
        return new PaymentEvent(UUID.randomUUID(), type, payment.getId(), payment.getMerchantId(),
                payment.getStatus().name(), payment.getAmount().minor(), payment.getRefunded().minor(),
                payment.getAmount().currency(), occurredAt);
    }
}
