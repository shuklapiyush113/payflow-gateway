package dev.payflow.payment.processor;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/**
 * Strategy for one payment method. Each method validates and masks its own source format and
 * talks to the bank in its own way; {@link ProcessorRegistry} picks the strategy at runtime.
 */
public interface PaymentMethodProcessor {

    PaymentMethod method();

    /** Throws {@link InvalidPaymentSourceException} when the source does not fit this method. */
    void validateSource(String source);

    /** Masked form safe to store and log, e.g. {@code tok_****4242}. */
    String mask(String source);

    BankResponse authorize(String source, Money amount);
}
