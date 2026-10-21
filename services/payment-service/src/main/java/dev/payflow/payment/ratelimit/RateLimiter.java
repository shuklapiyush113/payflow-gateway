package dev.payflow.payment.ratelimit;

/** Port for request rate limiting; {@link RedisTokenBucketRateLimiter} is the implementation. */
public interface RateLimiter {

    /** Takes one token from the bucket identified by {@code key}. */
    RateLimitDecision tryAcquire(String key);
}
