package dev.payflow.payment.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.Payment;
import dev.payflow.payment.processor.BankUnavailableException;

/**
 * Settles payments stuck in {@code CREATED} or {@code PENDING}: a bank timeout, or a crash between
 * saving the payment and recording the bank's answer. Each run claims a batch with
 * {@code FOR UPDATE SKIP LOCKED} and leases it for {@code retry-after}, so several replicas can run
 * the job without asking the bank about the same payment at once. A payment whose bank lookup fails
 * is picked up again once its lease runs out.
 *
 * <p>{@code stale-after} must be longer than the bank call timeout; otherwise the job could ask the
 * bank about a request that is still in flight and mark it failed.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final PaymentService payments;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration staleAfter;
    private final Duration retryAfter;
    private final int batchSize;
    private final int alertAfterAttempts;

    public PaymentReconciler(PaymentService payments, JdbcClient jdbc, Clock clock,
                             @Value("${payflow.reconciliation.stale-after:2m}") Duration staleAfter,
                             @Value("${payflow.reconciliation.retry-after:1m}") Duration retryAfter,
                             @Value("${payflow.reconciliation.batch-size:50}") int batchSize,
                             @Value("${payflow.reconciliation.alert-after-attempts:10}") int alertAfterAttempts) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.retryAfter = retryAfter;
        this.batchSize = batchSize;
        this.alertAfterAttempts = alertAfterAttempts;
    }

    /** Returns how many payments this run settled. */
    @Scheduled(fixedDelayString = "${payflow.reconciliation.interval:30s}")
    public int reconcileBatch() {
        int settled = 0;
        for (Claimed claimed : claimBatch()) {
            try {
                Payment payment = payments.reconcile(claimed.id());
                if (!payment.getStatus().awaitsBankOutcome()) {
                    settled++;
                    log.info("reconciled payment {} to {}", payment.getId(), payment.getStatus());
                }
            } catch (BankUnavailableException e) {
                logRetry(claimed, e);
            } catch (RuntimeException e) {
                // e.g. an optimistic-lock conflict with the original request; the next run looks again.
                logRetry(claimed, e);
            }
        }
        return settled;
    }

    /** One auto-committed statement: claim, lease and count the attempt together. */
    private List<Claimed> claimBatch() {
        Instant now = clock.instant();
        return jdbc.sql("""
                        update payments set next_reconcile_at = ?, reconcile_attempts = reconcile_attempts + 1
                        where id in (
                            select id from payments
                            where status in ('CREATED', 'PENDING') and updated_at < ?
                              and (next_reconcile_at is null or next_reconcile_at <= ?)
                            order by updated_at
                            limit ?
                            for update skip locked)
                        returning id, reconcile_attempts
                        """)
                .params(Timestamp.from(now.plus(retryAfter)), Timestamp.from(now.minus(staleAfter)),
                        Timestamp.from(now), batchSize)
                .query(Claimed.class)
                .list();
    }

    private void logRetry(Claimed claimed, RuntimeException e) {
        if (claimed.reconcileAttempts() >= alertAfterAttempts) {
            log.error("payment {} still unsettled after {} reconciliation attempts: {}",
                    claimed.id(), claimed.reconcileAttempts(), e.getMessage());
        } else {
            log.warn("reconciling payment {} failed (attempt {}), retrying in {}: {}",
                    claimed.id(), claimed.reconcileAttempts(), retryAfter, e.getMessage());
        }
    }

    private record Claimed(UUID id, int reconcileAttempts) {
    }
}
