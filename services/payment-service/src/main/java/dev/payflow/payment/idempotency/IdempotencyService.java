package dev.payflow.payment.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/**
 * Makes POST endpoints safe to retry. The first request with a key claims it with an atomic
 * {@code INSERT ... ON CONFLICT DO NOTHING}; retries with the same key and body get the stored
 * response instead of charging again.
 *
 * <ul>
 *   <li>Same key, different body → {@link IdempotencyKeyReusedException} (422)</li>
 *   <li>Same key while the first request is still running → {@link IdempotencyInProgressException} (409)</li>
 *   <li>The action throws → the key is released so the client can retry</li>
 *   <li>A claim left behind by a crashed instance is taken over after {@code lock-timeout}</li>
 * </ul>
 *
 * Each statement auto-commits on its own; callers must not wrap this in a transaction.
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration lockTimeout;
    private final Duration retention;

    public IdempotencyService(JdbcClient jdbc, JsonMapper json, Clock clock,
                              @Value("${payflow.idempotency.lock-timeout:60s}") Duration lockTimeout,
                              @Value("${payflow.idempotency.retention:24h}") Duration retention) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.lockTimeout = lockTimeout;
        this.retention = retention;
    }

    public <T> Result<T> execute(String merchantId, String key, String operation, Object request,
                                 Class<T> responseType, Supplier<T> action) {
        String requestHash = hash(operation + "\n" + json.writeValueAsString(request));
        if (!claim(merchantId, key, requestHash)) {
            return replay(merchantId, key, requestHash, responseType);
        }
        T response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            release(merchantId, key);
            throw e;
        }
        jdbc.sql("""
                        update idempotency_keys set status = 'COMPLETED', response_body = ?, completed_at = ?
                        where merchant_id = ? and idem_key = ?
                        """)
                .params(json.writeValueAsString(response), now(), merchantId, key)
                .update();
        return new Result<>(response, false);
    }

    private boolean claim(String merchantId, String key, String requestHash) {
        Timestamp now = now();
        int inserted = jdbc.sql("""
                        insert into idempotency_keys (merchant_id, idem_key, request_hash, status, created_at)
                        values (?, ?, ?, 'IN_PROGRESS', ?)
                        on conflict do nothing
                        """)
                .params(merchantId, key, requestHash, now)
                .update();
        if (inserted == 1) {
            return true;
        }
        // Take over a claim abandoned by a crashed instance (same request only).
        int takenOver = jdbc.sql("""
                        update idempotency_keys set created_at = ?
                        where merchant_id = ? and idem_key = ? and request_hash = ?
                          and status = 'IN_PROGRESS' and created_at < ?
                        """)
                .params(now, merchantId, key, requestHash, Timestamp.from(clock.instant().minus(lockTimeout)))
                .update();
        return takenOver == 1;
    }

    private <T> Result<T> replay(String merchantId, String key, String requestHash, Class<T> responseType) {
        Optional<StoredKey> stored = jdbc.sql("""
                        select request_hash, status, response_body from idempotency_keys
                        where merchant_id = ? and idem_key = ?
                        """)
                .params(merchantId, key)
                .query(StoredKey.class)
                .optional();
        if (stored.isEmpty()) {
            // Released between our insert attempt and this read; the client can simply retry.
            throw new IdempotencyInProgressException(key);
        }
        StoredKey row = stored.get();
        if (!row.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (!"COMPLETED".equals(row.status())) {
            throw new IdempotencyInProgressException(key);
        }
        return new Result<>(json.readValue(row.responseBody(), responseType), true);
    }

    private void release(String merchantId, String key) {
        jdbc.sql("delete from idempotency_keys where merchant_id = ? and idem_key = ? and status = 'IN_PROGRESS'")
                .params(merchantId, key)
                .update();
    }

    @Scheduled(fixedDelayString = "${payflow.idempotency.cleanup-interval:1h}")
    public int purgeExpired() {
        return jdbc.sql("delete from idempotency_keys where created_at < ?")
                .param(Timestamp.from(clock.instant().minus(retention)))
                .update();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code replayed} is true when the response came from storage rather than a new execution. */
    public record Result<T>(T response, boolean replayed) {
    }

    private record StoredKey(String requestHash, String status, String responseBody) {
    }
}
