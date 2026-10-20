package dev.payflow.payment.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.Payment;
import dev.payflow.payment.domain.PaymentMethod;
import dev.payflow.payment.domain.PaymentNotFoundException;
import dev.payflow.payment.domain.PaymentRepository;
import dev.payflow.payment.ledger.LedgerService;
import dev.payflow.payment.outbox.OutboxWriter;
import dev.payflow.payment.outbox.PaymentEvent;
import dev.payflow.payment.processor.BankConnector;
import dev.payflow.payment.processor.BankResponse;
import dev.payflow.payment.processor.BankTimeoutException;
import dev.payflow.payment.processor.BankUnavailableException;
import dev.payflow.payment.processor.PaymentMethodProcessor;
import dev.payflow.payment.processor.ProcessorRegistry;
import dev.payflow.payment.risk.RiskAssessment;
import dev.payflow.payment.risk.RiskScorer;

/**
 * Payment use cases. Each state change, its ledger journal and its outbox event commit in one
 * transaction. The bank call happens between transactions, so no database connection or row lock
 * is held while waiting on the network.
 */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final ProcessorRegistry processors;
    private final BankConnector bankConnector;
    private final RiskScorer riskScorer;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, ProcessorRegistry processors, BankConnector bankConnector,
                          RiskScorer riskScorer, LedgerService ledger, OutboxWriter outbox,
                          PlatformTransactionManager txManager, Clock clock) {
        this.payments = payments;
        this.processors = processors;
        this.bankConnector = bankConnector;
        this.riskScorer = riskScorer;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * Creates and authorizes a payment. {@code idempotencyKey} is stored on the payment with a unique
     * constraint: if a crash lost the stored idempotent response, the retry finds this payment here
     * instead of charging again.
     */
    public Payment create(String merchantId, String idempotencyKey, CreatePaymentCommand command) {
        PaymentMethodProcessor processor = processors.forMethod(command.method());
        processor.validateSource(command.source());
        Money amount = Money.of(command.amountMinor(), command.currency());

        Optional<Payment> existing = payments.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 1. Persist first, so a crash during the bank call leaves a CREATED record to reconcile.
        Payment created;
        try {
            created = tx.execute(status -> payments.save(new Payment(UUID.randomUUID(), merchantId, idempotencyKey,
                    amount, command.method(), processor.mask(command.source()), command.capture(),
                    command.description(), clock.instant())));
        } catch (DataIntegrityViolationException e) {
            // Another instance took over the same key and inserted first.
            return payments.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey).orElseThrow(() -> e);
        }
        UUID id = created.getId();

        // 2. Fraud check.
        RiskAssessment risk = riskScorer.assess(merchantId, command.method(), command.source(), amount);
        if (risk.block()) {
            return update(id, payment -> {
                payment.decline(risk.reason(), risk.score(), clock.instant());
                outbox.append(PaymentEvent.of("payment.declined", payment, clock.instant()));
            });
        }

        // 3. Authorize with the bank, outside any transaction.
        BankResponse bank;
        try {
            bank = processor.authorize(id, command.source(), amount);
        } catch (BankUnavailableException e) {
            return update(id, payment -> {
                payment.fail("bank_unavailable", clock.instant());
                outbox.append(PaymentEvent.of("payment.failed", payment, clock.instant()));
            });
        } catch (BankTimeoutException e) {
            // The bank may have approved; failing the payment here could lose a real charge.
            return update(id, payment -> {
                payment.markPending(clock.instant());
                outbox.append(PaymentEvent.of("payment.pending", payment, clock.instant()));
            });
        }

        // 4. Record the outcome, and capture straight away if the merchant asked for it.
        return update(id, payment -> recordBankResponse(payment, bank, risk.score()));
    }

    /**
     * Settles a payment whose bank outcome was never recorded (bank timeout, or a crash mid-request)
     * by asking the bank. Called by {@link PaymentReconciler}; the bank lookup runs outside any
     * transaction. Throws {@link BankUnavailableException} if the bank cannot be reached; the
     * reconciler then tries again later.
     */
    public Payment reconcile(UUID paymentId) {
        Optional<BankResponse> bank = bankConnector.lookup(paymentId);
        return update(paymentId, payment -> {
            if (!payment.getStatus().awaitsBankOutcome()) {
                return; // settled by the original request or another reconciler in the meantime
            }
            if (bank.isEmpty()) {
                // The bank never got the request, so nothing was charged.
                payment.fail("not_received_by_bank", clock.instant());
                outbox.append(PaymentEvent.of("payment.failed", payment, clock.instant()));
                return;
            }
            recordBankResponse(payment, bank.get(), payment.getRiskScore());
        });
    }

    public Payment capture(String merchantId, UUID paymentId) {
        return update(merchantId, paymentId, this::captureInTransaction);
    }

    public Payment voidAuthorization(String merchantId, UUID paymentId) {
        return update(merchantId, paymentId, payment -> {
            payment.voidAuthorization(clock.instant());
            outbox.append(PaymentEvent.of("payment.voided", payment, clock.instant()));
        });
    }

    public Payment refund(String merchantId, UUID paymentId, long amountMinor) {
        return update(merchantId, paymentId, payment -> {
            Money refund = Money.of(amountMinor, payment.getAmount().currency());
            payment.refund(refund, clock.instant());
            ledger.recordRefund(payment, refund);
            outbox.append(PaymentEvent.of("payment.refunded", payment, clock.instant()));
        });
    }

    public Payment get(String merchantId, UUID paymentId) {
        return payments.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private void recordBankResponse(Payment payment, BankResponse bank, Double riskScore) {
        if (!bank.approved()) {
            payment.decline(bank.declineReason(), riskScore, clock.instant());
            outbox.append(PaymentEvent.of("payment.declined", payment, clock.instant()));
            return;
        }
        payment.authorize(riskScore, bank.bankReference(), clock.instant());
        outbox.append(PaymentEvent.of("payment.authorized", payment, clock.instant()));
        if (payment.isAutoCapture()) {
            captureInTransaction(payment);
        }
    }

    private void captureInTransaction(Payment payment) {
        payment.capture(clock.instant());
        ledger.recordCapture(payment);
        outbox.append(PaymentEvent.of("payment.captured", payment, clock.instant()));
    }

    private Payment update(UUID paymentId, Consumer<Payment> change) {
        return tx.execute(status -> {
            Payment payment = payments.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
            change.accept(payment);
            // Flush inside the transaction so an optimistic-lock conflict surfaces here.
            return payments.saveAndFlush(payment);
        });
    }

    private Payment update(String merchantId, UUID paymentId, Consumer<Payment> change) {
        return tx.execute(status -> {
            Payment payment = get(merchantId, paymentId);
            change.accept(payment);
            return payments.saveAndFlush(payment);
        });
    }

    public record CreatePaymentCommand(long amountMinor, String currency, PaymentMethod method, String source,
                                       boolean capture, String description) {
    }
}
