package dev.payflow.payment.ratelimit;

public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterMillis;

    public RateLimitExceededException(long retryAfterMillis) {
        super("rate limit exceeded; retry in " + retryAfterMillis + " ms");
        this.retryAfterMillis = retryAfterMillis;
    }

    /** Whole seconds for the {@code Retry-After} header, rounded up so clients never retry too early. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfterMillis + 999) / 1000);
    }
}
