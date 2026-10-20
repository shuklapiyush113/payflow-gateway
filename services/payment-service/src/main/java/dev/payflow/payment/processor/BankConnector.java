package dev.payflow.payment.processor;

import java.util.Optional;
import java.util.UUID;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/**
 * Port to the acquiring bank or payment network. Production would have one adapter per bank;
 * this project ships {@link MockBankConnector}.
 *
 * <p>Every authorization carries our payment id as its reference. The bank dedupes on it, and
 * {@link #lookup} uses it to find out what happened to a request whose answer was lost.
 */
public interface BankConnector {

    /**
     * Throws {@link BankUnavailableException} when the bank could not be reached (nothing was sent) and
     * {@link BankTimeoutException} when the request was sent but no answer came back.
     */
    BankResponse authorize(UUID reference, PaymentMethod method, String source, Money amount);

    /**
     * The bank's record of an earlier authorization, or empty if the bank never received it.
     * Throws {@link BankUnavailableException} when the bank cannot be reached.
     */
    Optional<BankResponse> lookup(UUID reference);
}
