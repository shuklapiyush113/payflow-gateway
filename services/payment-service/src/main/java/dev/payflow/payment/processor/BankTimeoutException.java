package dev.payflow.payment.processor;

/**
 * The request may have reached the bank but no answer came back, so the outcome is unknown. Unlike
 * {@link BankUnavailableException} this must not be treated as a failure: the bank may have approved.
 */
public class BankTimeoutException extends RuntimeException {

    public BankTimeoutException(String message) {
        super(message);
    }
}
