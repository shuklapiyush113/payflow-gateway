package dev.payflow.payment.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.payflow.payment.merchant.MerchantService;

/**
 * Reads {@code Authorization: Bearer <token>}. A merchant API key ({@code sk_…}) authenticates as that
 * merchant with {@code ROLE_MERCHANT}; the operator's admin token gets {@code ROLE_ADMIN}. Anything
 * else leaves the request anonymous, and the security chain answers 401.
 *
 * <p>The merchant id comes only from the key. A client-sent {@code X-Merchant-Id} is ignored.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final MerchantService merchants;
    /** SHA-256 of the admin token, or null when admin endpoints are disabled. */
    private final byte[] adminTokenHash;

    public ApiKeyAuthenticationFilter(MerchantService merchants, String adminToken) {
        this.merchants = merchants;
        this.adminTokenHash = adminToken == null || adminToken.isBlank() ? null : sha256(adminToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            authenticate(header.substring(BEARER.length()).trim()).ifPresent(authentication -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private Optional<Authentication> authenticate(String token) {
        if (adminTokenHash != null && MessageDigest.isEqual(sha256(token), adminTokenHash)) {
            return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                    "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        }
        return merchants.authenticate(token).map(owner -> UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedMerchant(owner.merchantId(), owner.apiKeyId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))));
    }

    /** Hashing both sides first makes the comparison constant-time regardless of token length. */
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
