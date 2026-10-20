package dev.payflow.payment.processor;

import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/**
 * Deterministic stand-in for a bank, driven by magic sources (like Stripe test cards):
 * <ul>
 *   <li>source containing {@code decline} → declined, {@code insufficient_funds}</li>
 *   <li>source containing {@code unavailable} → {@link BankUnavailableException}</li>
 *   <li>anything else → approved</li>
 * </ul>
 */
@Component
public class MockBankConnector implements BankConnector {

    @Override
    public BankResponse authorize(PaymentMethod method, String source, Money amount) {
        if (source.contains("unavailable")) {
            throw new BankUnavailableException("mock bank is unavailable");
        }
        if (source.contains("decline")) {
            return BankResponse.declined("insufficient_funds");
        }
        return BankResponse.approved("bank_" + UUID.randomUUID());
    }
}
