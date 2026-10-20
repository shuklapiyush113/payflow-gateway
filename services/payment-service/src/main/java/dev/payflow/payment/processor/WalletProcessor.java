package dev.payflow.payment.processor;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/** Wallet payments reference a wallet id such as {@code wal_8f3k2m}. */
@Component
public class WalletProcessor implements PaymentMethodProcessor {

    private static final Pattern WALLET = Pattern.compile("wal_[a-z0-9_]{4,64}");

    private final BankConnector bank;

    public WalletProcessor(BankConnector bank) {
        this.bank = bank;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.WALLET;
    }

    @Override
    public void validateSource(String source) {
        if (!WALLET.matcher(source).matches()) {
            throw new InvalidPaymentSourceException("wallet source must look like wal_8f3k2m");
        }
    }

    @Override
    public String mask(String source) {
        return "wal_****" + source.substring(source.length() - 4);
    }

    @Override
    public BankResponse authorize(String source, Money amount) {
        return bank.authorize(method(), source, amount);
    }
}
