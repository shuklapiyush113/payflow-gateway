package dev.payflow.payment.processor;

public class BankUnavailableException extends RuntimeException {

    public BankUnavailableException(String message) {
        super(message);
    }
}
