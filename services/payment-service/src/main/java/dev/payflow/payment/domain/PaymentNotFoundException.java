package dev.payflow.payment.domain;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID id) {
        super("payment " + id + " not found");
    }
}
