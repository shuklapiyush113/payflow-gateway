package dev.payflow.payment.application;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;

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
import dev.payflow.payment.processor.BankResponse;
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
    private final RiskScorer riskScorer;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, ProcessorRegistry processors, RiskScorer riskScorer,
                          LedgerService ledger, OutboxWriter outbox, PlatformTransactionManager txManager,
                          Clock clock) {
        this.payments = payments;
        this.processors = processors;
        this.riskScorer = riskScorer;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public Payment create(String merchantId, CreatePaymentCommand command) {
        PaymentMethodProcessor processor = processors.forMethod(command.method());
        processor.validateSource(command.source());
        Money amount = Money.of(command.amountMinor(), command.currency());

        // 1. Persist first, so a crash during the bank call leaves a CREATED record to reconcile.
        Payment created = tx.execute(status -> payments.save(new Payment(UUID.randomUUID(), merchantId, amount,
                command.method(), processor.mask(command.source()), command.description(), clock.instant())));
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
            bank = processor.authorize(command.source(), amount);
        } catch (BankUnavailableException e) {
            return update(id, payment -> {
                payment.fail("bank_unavailable", clock.instant());
                outbox.append(PaymentEvent.of("payment.failed", payment, clock.instant()));
            });
        }

        // 4. Record the outcome, and capture straight away if the merchant asked for it.
        return update(id, payment -> {
            if (!bank.approved()) {
                payment.decline(bank.declineReason(), risk.score(), clock.instant());
                outbox.append(PaymentEvent.of("payment.declined", payment, clock.instant()));
                return;
            }
            payment.authorize(risk.score(), bank.bankReference(), clock.instant());
            outbox.append(PaymentEvent.of("payment.authorized", payment, clock.instant()));
            if (command.capture()) {
                captureInTransaction(payment);
            }
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
