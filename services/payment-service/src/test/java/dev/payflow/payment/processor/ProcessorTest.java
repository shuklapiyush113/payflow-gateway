package dev.payflow.payment.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.payflow.payment.domain.Money;
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

    @Test
    void mockBankAnswersTheSameReferenceOnceAndRemembersIt() {
        MockBankConnector mock = new MockBankConnector();
        UUID ref = UUID.randomUUID();
        Money amount = Money.of(1_000, "INR");

        BankResponse first = mock.authorize(ref, PaymentMethod.CARD, "tok_visa_4242", amount);
        BankResponse resent = mock.authorize(ref, PaymentMethod.CARD, "tok_visa_4242", amount);
        assertThat(resent).isEqualTo(first);
        assertThat(mock.lookup(ref)).contains(first);
    }

    @Test
    void mockBankTimeoutsEitherApproveSilentlyOrNeverArrive() {
        MockBankConnector mock = new MockBankConnector();
        Money amount = Money.of(1_000, "INR");
        UUID approved = UUID.randomUUID();
        UUID lost = UUID.randomUUID();

        assertThatThrownBy(() -> mock.authorize(approved, PaymentMethod.CARD, "tok_timeout_card", amount))
                .isInstanceOf(BankTimeoutException.class);
        assertThatThrownBy(() -> mock.authorize(lost, PaymentMethod.CARD, "tok_lost_card", amount))
                .isInstanceOf(BankTimeoutException.class);

        assertThat(mock.lookup(approved)).get().extracting(BankResponse::approved).isEqualTo(true);
        assertThat(mock.lookup(lost)).isEmpty();
    }
}
