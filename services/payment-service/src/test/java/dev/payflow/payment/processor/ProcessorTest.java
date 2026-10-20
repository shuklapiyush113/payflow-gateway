package dev.payflow.payment.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.payflow.payment.domain.PaymentMethod;

class ProcessorTest {

    private final BankConnector bank = new MockBankConnector();
    private final ProcessorRegistry registry = new ProcessorRegistry(
            List.of(new CardProcessor(bank), new UpiProcessor(bank), new WalletProcessor(bank)));

    @Test
    void registryReturnsProcessorForEachMethod() {
        for (PaymentMethod method : PaymentMethod.values()) {
            assertThat(registry.forMethod(method).method()).isEqualTo(method);
        }
    }

    @Test
    void registryRejectsDuplicateProcessors() {
        assertThatThrownBy(() -> new ProcessorRegistry(List.of(new CardProcessor(bank), new CardProcessor(bank))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void eachMethodValidatesAndMasksItsOwnSourceFormat() {
        PaymentMethodProcessor card = registry.forMethod(PaymentMethod.CARD);
        card.validateSource("tok_visa_4242");
        assertThat(card.mask("tok_visa_4242")).isEqualTo("tok_****4242");
        assertThatThrownBy(() -> card.validateSource("4242424242424242"))
                .isInstanceOf(InvalidPaymentSourceException.class);

        PaymentMethodProcessor upi = registry.forMethod(PaymentMethod.UPI);
        upi.validateSource("alice@okbank");
        assertThat(upi.mask("alice@okbank")).isEqualTo("a****@okbank");
        assertThatThrownBy(() -> upi.validateSource("tok_visa_4242"))
                .isInstanceOf(InvalidPaymentSourceException.class);

        PaymentMethodProcessor wallet = registry.forMethod(PaymentMethod.WALLET);
        wallet.validateSource("wal_8f3k2m");
        assertThat(wallet.mask("wal_8f3k2m")).isEqualTo("wal_****3k2m");
    }
}
