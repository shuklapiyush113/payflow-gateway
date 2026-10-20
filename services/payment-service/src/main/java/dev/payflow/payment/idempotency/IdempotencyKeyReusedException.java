package dev.payflow.payment.idempotency;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("idempotency key " + key + " was already used with a different request");
    }
}
