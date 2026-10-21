package dev.payflow.payment.ratelimit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Token bucket in Redis ({@code ratelimit/token_bucket.lua}). The script reads, refills and takes a
 * token in one atomic step, so concurrent requests on any number of replicas cannot overspend a bucket.
 *
 * <p>Fails open: if Redis is unreachable the request is allowed and counted as {@code unavailable}.
 * For a payments API, rejecting every payment because a rate limiter is down is worse than briefly
 * running without limits; Cloudflare still limits per IP at the edge.
 */
@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("ratelimit/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final long capacity;
    private final double refillPerSecond;
    private final Counter allowed;
    private final Counter rejected;
    private final Counter unavailable;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, MeterRegistry meters,
                                       @Value("${payflow.rate-limit.capacity:100}") long capacity,
                                       @Value("${payflow.rate-limit.refill-per-second:50}") double refillPerSecond) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("rate limit capacity must be >= 1 and refill rate > 0");
        }
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.allowed = meters.counter("payflow.ratelimit.decisions", "result", "allowed");
        this.rejected = meters.counter("payflow.ratelimit.decisions", "result", "rejected");
        this.unavailable = meters.counter("payflow.ratelimit.decisions", "result", "unavailable");
    }

    @Override
    public RateLimitDecision tryAcquire(String key) {
        List<?> result;
        try {
            result = redis.execute(SCRIPT, List.of(key), Long.toString(capacity), Double.toString(refillPerSecond));
        } catch (DataAccessException e) {
            unavailable.increment();
            log.warn("rate limiter unavailable, allowing request: {}", e.getMessage());
            return RateLimitDecision.unenforced();
        }
        boolean ok = ((Number) result.get(0)).longValue() == 1;
        (ok ? allowed : rejected).increment();
        return new RateLimitDecision(ok, true, capacity, ((Number) result.get(1)).longValue(),
                ((Number) result.get(2)).longValue());
    }
}
