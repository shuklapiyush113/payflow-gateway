package dev.payflow.payment.api;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.payflow.payment.domain.InvalidStateTransitionException;
import dev.payflow.payment.domain.PaymentNotFoundException;
import dev.payflow.payment.domain.RefundExceedsCaptureException;
import dev.payflow.payment.idempotency.IdempotencyInProgressException;
import dev.payflow.payment.idempotency.IdempotencyKeyReusedException;
import dev.payflow.payment.merchant.ApiKeyNotFoundException;
import dev.payflow.payment.merchant.MerchantNotFoundException;
import dev.payflow.payment.processor.InvalidPaymentSourceException;

/** Maps exceptions to RFC 9457 problem details. Spring's own errors (validation, missing headers) are handled by the base class. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthorized(AuthenticationException e) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized",
                "missing, unknown or revoked API key; send Authorization: Bearer <key>");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "this credential cannot use this endpoint");
    }

    @ExceptionHandler({MerchantNotFoundException.class, ApiKeyNotFoundException.class})
    ProblemDetail adminNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail notFound(PaymentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "payment_not_found", e.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ProblemDetail invalidTransition(InvalidStateTransitionException e) {
        return problem(HttpStatus.CONFLICT, "invalid_state_transition", e.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate(OptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "concurrent_update", "payment was modified concurrently; retry");
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    ProblemDetail inProgress(IdempotencyInProgressException e) {
        return problem(HttpStatus.CONFLICT, "idempotency_in_progress", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused", e.getMessage());
    }

    @ExceptionHandler(RefundExceedsCaptureException.class)
    ProblemDetail refundTooLarge(RefundExceedsCaptureException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "refund_exceeds_capture", e.getMessage());
    }

    @ExceptionHandler({InvalidPaymentSourceException.class, IllegalArgumentException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code);
        problem.setProperty("code", code);
        return problem;
    }
}
