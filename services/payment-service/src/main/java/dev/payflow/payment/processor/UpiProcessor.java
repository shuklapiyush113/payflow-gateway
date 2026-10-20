package dev.payflow.payment.processor;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/** UPI payments use a virtual payment address such as {@code alice@okbank}. */
@Component
public class UpiProcessor implements PaymentMethodProcessor {

    private static final Pattern VPA = Pattern.compile("[a-z0-9._-]{2,64}@[a-z]{2,32}");

    private final BankConnector bank;

    public UpiProcessor(BankConnector bank) {
        this.bank = bank;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.UPI;
    }

    @Override
    public void validateSource(String source) {
        if (!VPA.matcher(source).matches()) {
            throw new InvalidPaymentSourceException("UPI source must be a VPA like alice@okbank");
        }
    }

    @Override
    public String mask(String source) {
        int at = source.indexOf('@');
        return source.charAt(0) + "****" + source.substring(at);
    }

    @Override
    public BankResponse authorize(String source, Money amount) {
        return bank.authorize(method(), source, amount);
    }
}
