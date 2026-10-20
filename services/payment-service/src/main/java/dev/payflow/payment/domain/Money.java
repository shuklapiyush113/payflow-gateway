package dev.payflow.payment.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * Amount in minor units (paise, cents). Never use floating point for money.
 */
public record Money(long minor, String currency) {

    public Money {
        if (minor < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        Objects.requireNonNull(currency, "currency");
        // Throws IllegalArgumentException for unknown ISO 4217 codes.
        currency = Currency.getInstance(currency.toUpperCase()).getCurrencyCode();
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    /** Percentage in basis points (1 bp = 0.01%), rounded half up. */
    public Money percentage(int basisPoints) {
        return new Money(Math.round(minor * basisPoints / 10_000.0), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minor > other.minor;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
