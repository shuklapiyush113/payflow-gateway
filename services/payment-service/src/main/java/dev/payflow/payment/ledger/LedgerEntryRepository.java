package dev.payflow.payment.ledger;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByPaymentId(UUID paymentId);

    /** Signed balance per account and currency: debits positive, credits negative. */
    @Query("""
            select e.account as account, e.currency as currency,
                   sum(case when e.direction = dev.payflow.payment.ledger.EntryDirection.DEBIT
                            then e.amountMinor else -e.amountMinor end) as balanceMinor
            from LedgerEntry e
            where e.merchantId = :merchantId
            group by e.account, e.currency
            """)
    List<AccountBalance> balancesForMerchant(String merchantId);

    interface AccountBalance {
        LedgerAccount getAccount();

        String getCurrency();

        long getBalanceMinor();
    }
}
