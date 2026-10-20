package dev.payflow.payment.processor;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/**
 * Port to the acquiring bank or payment network. Production would have one adapter per bank;
 * this project ships {@link MockBankConnector}.
 */
public interface BankConnector {

    /** Throws {@link BankUnavailableException} when the bank cannot be reached. */
    BankResponse authorize(PaymentMethod method, String source, Money amount);
}
