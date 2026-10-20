package dev.payflow.payment.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.payflow.payment.api.ApiModels.BalanceResponse;
import dev.payflow.payment.api.ApiModels.CreatePaymentRequest;
import dev.payflow.payment.api.ApiModels.PaymentResponse;
import dev.payflow.payment.api.ApiModels.RefundRequest;
import dev.payflow.payment.application.PaymentService;
import dev.payflow.payment.idempotency.IdempotencyService;
import dev.payflow.payment.ledger.LedgerService;

/**
 * Merchant-facing payments API. The merchant id arrives in {@code X-Merchant-Id}; the API gateway
 * (a later step) will set it from the authenticated API key. Every POST needs an
 * {@code Idempotency-Key}; a replayed response carries {@code Idempotent-Replayed: true}.
 */
@RestController
@RequestMapping("/v1")
@Validated
public class PaymentController {

    static final String MERCHANT = "X-Merchant-Id";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final LedgerService ledger;

    public PaymentController(PaymentService payments, IdempotencyService idempotency, LedgerService ledger) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.ledger = ledger;
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> create(@RequestHeader(MERCHANT) @NotBlank String merchantId,
                                                  @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 255) String key,
                                                  @Valid @RequestBody CreatePaymentRequest request) {
        var result = idempotency.execute(merchantId, key, "create", request, PaymentResponse.class,
                () -> PaymentResponse.from(payments.create(merchantId, key, request.toCommand())));
        // 202 while the bank's answer is unknown; the client polls the Location or waits for the webhook.
        return respond(result.response().status().awaitsBankOutcome() ? HttpStatus.ACCEPTED : HttpStatus.CREATED,
                result);
    }

    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<PaymentResponse> capture(@RequestHeader(MERCHANT) @NotBlank String merchantId,
                                                   @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 255) String key,
                                                   @PathVariable UUID id) {
        var result = idempotency.execute(merchantId, key, "capture:" + id, id, PaymentResponse.class,
                () -> PaymentResponse.from(payments.capture(merchantId, id)));
        return respond(HttpStatus.OK, result);
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<PaymentResponse> voidAuthorization(@RequestHeader(MERCHANT) @NotBlank String merchantId,
                                                             @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 255) String key,
                                                             @PathVariable UUID id) {
        var result = idempotency.execute(merchantId, key, "void:" + id, id, PaymentResponse.class,
                () -> PaymentResponse.from(payments.voidAuthorization(merchantId, id)));
        return respond(HttpStatus.OK, result);
    }

    @PostMapping("/payments/{id}/refunds")
    public ResponseEntity<PaymentResponse> refund(@RequestHeader(MERCHANT) @NotBlank String merchantId,
                                                  @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 255) String key,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody RefundRequest request) {
        var result = idempotency.execute(merchantId, key, "refund:" + id, request, PaymentResponse.class,
                () -> PaymentResponse.from(payments.refund(merchantId, id, request.amountMinor())));
        return respond(HttpStatus.OK, result);
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse get(@RequestHeader(MERCHANT) @NotBlank String merchantId, @PathVariable UUID id) {
        return PaymentResponse.from(payments.get(merchantId, id));
    }

    @GetMapping("/balances")
    public List<BalanceResponse> balances(@RequestHeader(MERCHANT) @NotBlank String merchantId) {
        return ledger.balances(merchantId).stream().map(BalanceResponse::from).toList();
    }

    private static ResponseEntity<PaymentResponse> respond(HttpStatus status,
                                                           IdempotencyService.Result<PaymentResponse> result) {
        var builder = ResponseEntity.status(status).header("Idempotent-Replayed", String.valueOf(result.replayed()));
        if (status == HttpStatus.CREATED || status == HttpStatus.ACCEPTED) {
            builder.location(URI.create("/v1/payments/" + result.response().id()));
        }
        return builder.body(result.response());
    }
}
