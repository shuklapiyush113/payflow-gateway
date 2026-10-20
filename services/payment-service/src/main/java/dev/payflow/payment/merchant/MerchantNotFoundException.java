package dev.payflow.payment.merchant;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(String merchantId) {
        super("merchant " + merchantId + " not found");
    }
}
