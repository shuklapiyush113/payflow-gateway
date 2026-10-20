package dev.payflow.payment.processor;

public class InvalidPaymentSourceException extends RuntimeException {

    public InvalidPaymentSourceException(String message) {
        super(message);
    }
}
