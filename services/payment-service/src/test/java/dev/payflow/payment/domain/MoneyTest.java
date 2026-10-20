package dev.payflow.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalisesCurrencyCode() {
        assertThat(Money.of(100, "inr").currency()).isEqualTo("INR");
    }

    @Test
    void rejectsNegativeAmountsAndUnknownCurrencies() {
        assertThatThrownBy(() -> Money.of(-1, "INR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, "XYZ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentageRoundsHalfUp() {
        assertThat(Money.of(10_000, "INR").percentage(200).minor()).isEqualTo(200);
        assertThat(Money.of(125, "INR").percentage(200).minor()).isEqualTo(3); // 2.5 -> 3
        assertThat(Money.of(124, "INR").percentage(200).minor()).isEqualTo(2); // 2.48 -> 2
    }

    @Test
    void arithmeticRequiresSameCurrency() {
        assertThat(Money.of(500, "INR").minus(Money.of(200, "INR")).minor()).isEqualTo(300);
        assertThatThrownBy(() -> Money.of(500, "INR").plus(Money.of(1, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
