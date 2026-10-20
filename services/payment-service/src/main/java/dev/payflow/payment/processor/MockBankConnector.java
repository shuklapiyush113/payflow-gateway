package dev.payflow.payment.processor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/**
 * Deterministic stand-in for a bank, driven by magic sources (like Stripe test cards):
 * <ul>
 *   <li>source containing {@code decline} → declined, {@code insufficient_funds}</li>
 *   <li>source containing {@code unavailable} → {@link BankUnavailableException}, nothing recorded</li>
 *   <li>source containing {@code timeout} → approved at the bank, but the answer is lost
 *       ({@link BankTimeoutException})</li>
 *   <li>source containing {@code lost} → the request never reaches the bank ({@link BankTimeoutException})</li>
 *   <li>anything else → approved</li>
 * </ul>
 * Outcomes are kept in memory, so {@link #lookup} only knows about authorizations since startup.
 */
@Component
public class MockBankConnector implements BankConnector {

    private final Map<UUID, BankResponse> outcomes = new ConcurrentHashMap<>();

    @Override
    public BankResponse authorize(UUID reference, PaymentMethod method, String source, Money amount) {
        if (source.contains("unavailable")) {
            throw new BankUnavailableException("mock bank is unavailable");
        }
        if (source.contains("lost")) {
            throw new BankTimeoutException("mock bank did not answer");
        }
        // Same reference, same answer: a resent request never authorizes twice.
        BankResponse response = outcomes.computeIfAbsent(reference, ref -> source.contains("decline")
                ? BankResponse.declined("insufficient_funds")
                : BankResponse.approved("bank_" + UUID.randomUUID()));
        if (source.contains("timeout")) {
            throw new BankTimeoutException("mock bank approved but the answer was lost");
        }
        return response;
    }

    @Override
    public Optional<BankResponse> lookup(UUID reference) {
        return Optional.ofNullable(outcomes.get(reference));
    }
}
