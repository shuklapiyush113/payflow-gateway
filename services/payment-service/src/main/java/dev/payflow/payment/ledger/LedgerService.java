package dev.payflow.payment.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.Payment;

/**
 * Double-entry bookkeeping: every journal's debits equal its credits, so money is never created
 * or lost. Runs inside the caller's transaction, so the payment status and its journal commit together.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository entries;
    private final Properties properties;
    private final Clock clock;

    public LedgerService(LedgerEntryRepository entries, Properties properties, Clock clock) {
        this.entries = entries;
        this.properties = properties;
        this.clock = clock;
    }

    /** Capture: the bank owes us the full amount; we owe the merchant the amount minus our fee. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordCapture(Payment payment) {
        Money amount = payment.getAmount();
        Money fee = amount.percentage(properties.feeBasisPoints());
        return post(payment, List.of(
                line(LedgerAccount.PSP_RECEIVABLE, EntryDirection.DEBIT, amount),
                line(LedgerAccount.MERCHANT_PAYABLE, EntryDirection.CREDIT, amount.minus(fee)),
                line(LedgerAccount.FEE_REVENUE, EntryDirection.CREDIT, fee)));
    }

    /** Refund: the merchant bears the refund; the processing fee is not returned. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordRefund(Payment payment, Money refund) {
        return post(payment, List.of(
                line(LedgerAccount.MERCHANT_PAYABLE, EntryDirection.DEBIT, refund),
                line(LedgerAccount.PSP_RECEIVABLE, EntryDirection.CREDIT, refund)));
    }

    public List<LedgerEntryRepository.AccountBalance> balances(String merchantId) {
        return entries.balancesForMerchant(merchantId);
    }

    private UUID post(Payment payment, List<Line> lines) {
        long debits = sum(lines, EntryDirection.DEBIT);
        long credits = sum(lines, EntryDirection.CREDIT);
        if (debits != credits) {
            throw new IllegalStateException("unbalanced journal: debits " + debits + " != credits " + credits);
        }
        UUID journalId = UUID.randomUUID();
        Instant now = clock.instant();
        entries.saveAll(lines.stream()
                .filter(l -> l.amount().minor() > 0)
                .map(l -> new LedgerEntry(journalId, payment.getId(), payment.getMerchantId(), l.account(),
                        l.direction(), l.amount().minor(), l.amount().currency(), now))
                .toList());
        return journalId;
    }

    private static long sum(List<Line> lines, EntryDirection direction) {
        return lines.stream().filter(l -> l.direction() == direction).mapToLong(l -> l.amount().minor()).sum();
    }

    private static Line line(LedgerAccount account, EntryDirection direction, Money amount) {
        return new Line(account, direction, amount);
    }

    private record Line(LedgerAccount account, EntryDirection direction, Money amount) {
    }

    /** {@code payflow.ledger.fee-basis-points}: processing fee, e.g. 200 = 2%. */
    @ConfigurationProperties("payflow.ledger")
    public record Properties(int feeBasisPoints) {
    }
}
