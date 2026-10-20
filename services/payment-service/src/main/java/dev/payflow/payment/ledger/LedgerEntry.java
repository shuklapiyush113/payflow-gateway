package dev.payflow.payment.ledger;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One line of a journal. Entries are append-only: corrections are new journals, never updates. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryDirection direction;

    @Column(nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // for JPA
    }

    LedgerEntry(UUID journalId, UUID paymentId, String merchantId, LedgerAccount account,
                EntryDirection direction, long amountMinor, String currency, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.journalId = journalId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.account = account;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getJournalId() {
        return journalId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }
}
