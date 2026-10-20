package dev.payflow.payment.processor;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/** Cards arrive as tokens from a vault (never raw card numbers), e.g. {@code tok_visa_4242}. */
@Component
public class CardProcessor implements PaymentMethodProcessor {

    private static final Pattern TOKEN = Pattern.compile("tok_[a-z0-9_]{4,64}");

    private final BankConnector bank;

    public CardProcessor(BankConnector bank) {
        this.bank = bank;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CARD;
    }

    @Override
    public void validateSource(String source) {
        if (!TOKEN.matcher(source).matches()) {
            throw new InvalidPaymentSourceException("card source must be a token like tok_visa_4242");
        }
    }

    @Override
    public String mask(String source) {
        return "tok_****" + source.substring(source.length() - 4);
    }

    @Override
    public BankResponse authorize(UUID reference, String source, Money amount) {
        return bank.authorize(reference, method(), source, amount);
    }
}
