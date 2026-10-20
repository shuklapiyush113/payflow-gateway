package dev.payflow.payment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Payment aggregate. All status changes go through {@link #transitionTo}, which enforces the
 * {@link PaymentStatus} state machine. {@code @Version} gives optimistic locking, so two concurrent
 * captures of the same payment cannot both succeed.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    /** Masked payment source (card token, UPI handle, wallet id); never the raw value. */
    @Column(nullable = false)
    private String sourceMasked;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private long refundedMinor;

    private Double riskScore;

    private String bankReference;

    private String failureReason;

    private String description;

    /** Client's Idempotency-Key; unique per merchant, so a retry can never create a second payment. */
    private String idempotencyKey;

    /** Capture as soon as the bank approves; kept so the reconciler can finish what the request asked. */
    @Column(nullable = false)
    private boolean autoCapture;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(UUID id, String merchantId, String idempotencyKey, Money amount, PaymentMethod method,
                   String sourceMasked, boolean autoCapture, String description, Instant now) {
        if (amount.minor() == 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        this.id = id;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.amountMinor = amount.minor();
        this.currency = amount.currency();
        this.method = method;
        this.sourceMasked = sourceMasked;
        this.autoCapture = autoCapture;
        this.description = description;
        this.status = PaymentStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The bank call timed out; the bank may or may not have approved. */
    public void markPending(Instant now) {
        transitionTo(PaymentStatus.PENDING, now);
    }

    /** {@code riskScore} is null when the reconciler records an approval the request never saw. */
    public void authorize(Double riskScore, String bankReference, Instant now) {
        this.riskScore = riskScore;
        this.bankReference = bankReference;
        transitionTo(PaymentStatus.AUTHORIZED, now);
    }

    public void decline(String reason, Double riskScore, Instant now) {
        this.riskScore = riskScore;
        this.failureReason = reason;
        transitionTo(PaymentStatus.DECLINED, now);
    }

    public void fail(String reason, Instant now) {
        this.failureReason = reason;
        transitionTo(PaymentStatus.FAILED, now);
    }

    public void capture(Instant now) {
        transitionTo(PaymentStatus.CAPTURED, now);
    }

    public void voidAuthorization(Instant now) {
        transitionTo(PaymentStatus.VOIDED, now);
    }

    public void refund(Money refund, Instant now) {
        Money refundable = refundable();
        if (refund.minor() == 0 || refund.isGreaterThan(refundable)) {
            throw new RefundExceedsCaptureException(refund, refundable);
        }
        refundedMinor += refund.minor();
        transitionTo(refundedMinor == amountMinor ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED, now);
    }

    /** Amount that can still be refunded; zero until the payment is captured. */
    public Money refundable() {
        boolean captured = status == PaymentStatus.CAPTURED || status == PaymentStatus.PARTIALLY_REFUNDED;
        return Money.of(captured ? amountMinor - refundedMinor : 0, currency);
    }

    private void transitionTo(PaymentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateTransitionException(status, next);
        }
        status = next;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public Money getAmount() {
        return Money.of(amountMinor, currency);
    }

    public Money getRefunded() {
        return Money.of(refundedMinor, currency);
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getSourceMasked() {
        return sourceMasked;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Double getRiskScore() {
        return riskScore;
    }

    public String getBankReference() {
        return bankReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public boolean isAutoCapture() {
        return autoCapture;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
