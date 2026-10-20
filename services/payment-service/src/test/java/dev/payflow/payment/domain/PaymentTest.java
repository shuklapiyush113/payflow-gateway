package dev.payflow.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    private static Payment newPayment(long amount) {
        return new Payment(UUID.randomUUID(), "m_1", "idem_1", Money.of(amount, "INR"), PaymentMethod.CARD,
                "tok_****4242", false, null, NOW);
    }

    private static Payment captured(long amount) {
        Payment payment = newPayment(amount);
        payment.authorize(0.1, "bank_1", NOW);
        payment.capture(NOW);
        return payment;
    }

    @Test
    void happyPathMovesThroughLifecycle() {
        Payment payment = newPayment(10_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);

        payment.authorize(0.2, "bank_1", NOW);
        payment.capture(NOW);
        payment.refund(Money.of(4_000, "INR"), NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.refundable().minor()).isEqualTo(6_000);

        payment.refund(Money.of(6_000, "INR"), NOW);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getStatus().isTerminal()).isTrue();
    }

    @Test
    void cannotCaptureBeforeAuthorization() {
        assertThatThrownBy(() -> newPayment(10_000).capture(NOW))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CREATED to CAPTURED");
    }

    @Test
    void cannotCaptureTwice() {
        Payment payment = captured(10_000);
        assertThatThrownBy(() -> payment.capture(NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cannotRefundMoreThanCaptured() {
        Payment payment = captured(10_000);
        payment.refund(Money.of(7_000, "INR"), NOW);
        assertThatThrownBy(() -> payment.refund(Money.of(3_001, "INR"), NOW))
                .isInstanceOf(RefundExceedsCaptureException.class);
        assertThat(payment.getRefunded().minor()).isEqualTo(7_000);
    }

    @Test
    void cannotRefundAnAuthorizationThatWasNeverCaptured() {
        Payment payment = newPayment(10_000);
        payment.authorize(0.1, "bank_1", NOW);
        assertThatThrownBy(() -> payment.refund(Money.of(1, "INR"), NOW))
                .isInstanceOf(RefundExceedsCaptureException.class);
    }

    @Test
    void refundInOtherCurrencyIsRejected() {
        Payment payment = captured(10_000);
        assertThatThrownBy(() -> payment.refund(Money.of(100, "USD"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingPaymentIsSettledByTheBanksAnswer() {
        Payment approved = newPayment(10_000);
        approved.markPending(NOW);
        assertThat(approved.getStatus().awaitsBankOutcome()).isTrue();
        approved.authorize(null, "bank_1", NOW);
        assertThat(approved.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(approved.getStatus().awaitsBankOutcome()).isFalse();

        Payment lost = newPayment(10_000);
        lost.markPending(NOW);
        lost.fail("not_received_by_bank", NOW);
        assertThat(lost.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void pendingCannotBeCapturedOrMarkedPendingAgain() {
        Payment payment = newPayment(10_000);
        payment.markPending(NOW);
        assertThatThrownBy(() -> payment.capture(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> payment.markPending(NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"REFUNDED", "VOIDED", "DECLINED", "FAILED"})
    void terminalStatesAllowNoTransitions(PaymentStatus terminal) {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(terminal.canTransitionTo(next)).isFalse();
        }
    }
}
