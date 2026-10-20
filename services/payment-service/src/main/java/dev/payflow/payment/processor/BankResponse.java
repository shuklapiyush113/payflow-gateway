package dev.payflow.payment.processor;

/** Outcome of a bank authorization. {@code declineReason} is set only when {@code approved} is false. */
public record BankResponse(boolean approved, String bankReference, String declineReason) {

    public static BankResponse approved(String bankReference) {
        return new BankResponse(true, bankReference, null);
    }

    public static BankResponse declined(String reason) {
        return new BankResponse(false, null, reason);
    }
}
