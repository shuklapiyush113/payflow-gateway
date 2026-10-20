package dev.payflow.payment.domain;

public class RefundExceedsCaptureException extends RuntimeException {

    public RefundExceedsCaptureException(Money requested, Money refundable) {
        super("refund of " + requested.minor() + " exceeds refundable amount " + refundable.minor());
    }
}
