package dev.payflow.payment.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import dev.payflow.payment.security.AuthenticatedMerchant;

/**
 * One bucket per merchant, checked after authentication so the limit follows the merchant across all
 * of its API keys. Every response carries {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining};
 * a rejection becomes {@code 429} with {@code Retry-After} (see {@code ApiExceptionHandler}).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedMerchant merchant)) {
            return true; // security already rejects unauthenticated /v1 calls
        }
        RateLimitDecision decision = limiter.tryAcquire("ratelimit:merchant:" + merchant.merchantId());
        if (decision.enforced()) {
            response.setHeader("X-RateLimit-Limit", Long.toString(decision.limit()));
            response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        }
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterMillis());
        }
        return true;
    }
}
