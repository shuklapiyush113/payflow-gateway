package dev.payflow.payment.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import dev.payflow.payment.application.PaymentService.CreatePaymentCommand;
import dev.payflow.payment.domain.Payment;
import dev.payflow.payment.domain.PaymentMethod;
import dev.payflow.payment.domain.PaymentStatus;
import dev.payflow.payment.ledger.LedgerAccount;
import dev.payflow.payment.ledger.LedgerEntryRepository.AccountBalance;

/** Request and response bodies; kept separate from the domain so the API can evolve on its own. */
public final class ApiModels {

    private ApiModels() {
    }

    public record CreatePaymentRequest(
            @Positive long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
            @NotNull PaymentMethod method,
            @NotBlank @Size(max = 128) String source,
            boolean capture,
            @Size(max = 255) String description) {

        CreatePaymentCommand toCommand() {
            return new CreatePaymentCommand(amountMinor, currency, method, source, capture, description);
        }
    }

    public record RefundRequest(@Positive long amountMinor) {
    }

    public record PaymentResponse(UUID id, PaymentStatus status, long amountMinor, long refundedMinor,
                                  String currency, PaymentMethod method, String source, Double riskScore,
                                  String failureReason, String description, Instant createdAt, Instant updatedAt) {

        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getStatus(), p.getAmount().minor(), p.getRefunded().minor(),
                    p.getAmount().currency(), p.getMethod(), p.getSourceMasked(), p.getRiskScore(),
                    p.getFailureReason(), p.getDescription(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    /** Signed balance: positive means net debit (asset), negative means net credit (liability or revenue). */
    public record BalanceResponse(LedgerAccount account, String currency, long balanceMinor) {

        static BalanceResponse from(AccountBalance b) {
            return new BalanceResponse(b.getAccount(), b.getCurrency(), b.getBalanceMinor());
        }
    }
}
