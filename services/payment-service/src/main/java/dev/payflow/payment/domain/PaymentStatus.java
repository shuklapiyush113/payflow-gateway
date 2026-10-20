package dev.payflow.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Payment lifecycle as an explicit state machine. Every status lists the statuses it may move to;
 * {@link Payment} refuses any other transition.
 *
 * <pre>
 * CREATED ──► AUTHORIZED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *  │  │             │             └───────────────────────────────────▲
 *  │  ├──► DECLINED └──► VOIDED
 *  │  └──► FAILED
 *  └──► PENDING ──► AUTHORIZED | DECLINED | FAILED
 * </pre>
 *
 * {@code PENDING} means the bank call timed out and the outcome is unknown; the reconciler asks the
 * bank and settles it. A payment left in {@code CREATED} by a crash is settled the same way.
 */
public enum PaymentStatus {
    CREATED,
    PENDING,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED,
    DECLINED,
    FAILED;

    private Set<PaymentStatus> allowedNext;

    static {
        CREATED.allowedNext = EnumSet.of(PENDING, AUTHORIZED, DECLINED, FAILED);
        PENDING.allowedNext = EnumSet.of(AUTHORIZED, DECLINED, FAILED);
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

    /** True while the bank's answer has not been recorded; the reconciler looks for these. */
    public boolean awaitsBankOutcome() {
        return this == CREATED || this == PENDING;
    }

    public boolean isTerminal() {
        return allowedNext.isEmpty();
    }
}
