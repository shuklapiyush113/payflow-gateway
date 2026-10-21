package dev.payflow.payment.ratelimit;

/**
 * Result of one rate-limit check. {@code enforced} is false when the limiter could not be consulted
 * (Redis down) and the request was let through; the limit headers are then omitted.
 */
public record RateLimitDecision(boolean allowed, boolean enforced, long limit, long remaining, long retryAfterMillis) {

    static RateLimitDecision unenforced() {
        return new RateLimitDecision(true, false, 0, 0, 0);
    }
}
