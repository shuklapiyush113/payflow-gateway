package dev.payflow.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Payment lifecycle as an explicit state machine. Every status lists the statuses it may move to;
 * {@link Payment} refuses any other transition.
 *
 * <pre>
 * CREATED ──► AUTHORIZED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *    │             │             └───────────────────────────────────▲
 *    ├──► DECLINED └──► VOIDED
 *    └──► FAILED
 * </pre>
 */
public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED,
    DECLINED,
    FAILED;

    private Set<PaymentStatus> allowedNext;

    static {
        CREATED.allowedNext = EnumSet.of(AUTHORIZED, DECLINED, FAILED);
        AUTHORIZED.allowedNext = EnumSet.of(CAPTURED, VOIDED);
        CAPTURED.allowedNext = EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
        PARTIALLY_REFUNDED.allowedNext = EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);
        REFUNDED.allowedNext = EnumSet.noneOf(PaymentStatus.class);
        VOIDED.allowedNext = EnumSet.noneOf(PaymentStatus.class);
        DECLINED.allowedNext = EnumSet.noneOf(PaymentStatus.class);
        FAILED.allowedNext = EnumSet.noneOf(PaymentStatus.class);
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext.contains(next);
    }

    public boolean isTerminal() {
        return allowedNext.isEmpty();
    }
}
