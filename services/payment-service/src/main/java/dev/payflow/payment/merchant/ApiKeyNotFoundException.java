package dev.payflow.payment.merchant;

import java.util.UUID;

public class ApiKeyNotFoundException extends RuntimeException {

    public ApiKeyNotFoundException(UUID keyId) {
        super("API key " + keyId + " not found");
    }
}
