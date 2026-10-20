package dev.payflow.payment.domain;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("cannot move payment from " + from + " to " + to);
    }
}
