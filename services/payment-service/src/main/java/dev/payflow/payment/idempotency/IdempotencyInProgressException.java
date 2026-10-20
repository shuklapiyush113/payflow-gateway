package dev.payflow.payment.idempotency;

public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String key) {
        super("a request with idempotency key " + key + " is still in progress; retry shortly");
    }
}
