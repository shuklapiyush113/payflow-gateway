package dev.payflow.payment.ledger;

/**
 * Chart of accounts, kept per merchant.
 * <ul>
 *   <li>{@code PSP_RECEIVABLE}: money the bank owes us for captured payments (asset)</li>
 *   <li>{@code MERCHANT_PAYABLE}: money we owe the merchant, paid out at settlement (liability)</li>
 *   <li>{@code FEE_REVENUE}: our processing fee (revenue)</li>
 * </ul>
 */
public enum LedgerAccount {
    PSP_RECEIVABLE,
    MERCHANT_PAYABLE,
    FEE_REVENUE
}
