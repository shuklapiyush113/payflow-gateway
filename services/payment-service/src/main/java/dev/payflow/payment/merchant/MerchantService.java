package dev.payflow.payment.merchant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchants and their API keys. A key is 32 random bytes shown to the merchant once; only its SHA-256
 * hash is stored, so a database leak does not leak usable keys. A slow hash (bcrypt) is not needed:
 * it protects low-entropy passwords from guessing, and a 256-bit random key cannot be guessed, while
 * bcrypt would add ~100 ms to every API call.
 */
@Service
public class MerchantService {

    private static final String KEY_PREFIX = "sk_";
    /** Characters of the key kept in clear so merchants can tell their keys apart. */
    private static final int DISPLAY_PREFIX_LENGTH = KEY_PREFIX.length() + 8;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public MerchantService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public NewMerchant createMerchant(String name) {
        byte[] id = new byte[8];
        random.nextBytes(id);
        String merchantId = "m_" + HexFormat.of().formatHex(id);
        jdbc.sql("insert into merchants (id, name, created_at) values (?, ?, ?)")
                .params(merchantId, name, Timestamp.from(clock.instant()))
                .update();
        return new NewMerchant(merchantId, name, issueKey(merchantId));
    }

    /** Merchants may hold several active keys, so a key can be rotated without downtime. */
    @Transactional
    public IssuedApiKey issueKey(String merchantId) {
        requireMerchant(merchantId);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID id = UUID.randomUUID();
        String displayPrefix = key.substring(0, DISPLAY_PREFIX_LENGTH);
        jdbc.sql("""
                        insert into api_keys (id, merchant_id, key_hash, key_prefix, created_at)
                        values (?, ?, ?, ?, ?)
                        """)
                .params(id, merchantId, hash(key), displayPrefix, Timestamp.from(clock.instant()))
                .update();
        return new IssuedApiKey(id, key, displayPrefix);
    }

    public List<ApiKeySummary> listKeys(String merchantId) {
        requireMerchant(merchantId);
        return jdbc.sql("""
                        select id, key_prefix, created_at, revoked_at from api_keys
                        where merchant_id = ? order by created_at
                        """)
                .param(merchantId)
                .query(ApiKeySummary.class)
                .list();
    }

    public void revokeKey(UUID keyId) {
        int updated = jdbc.sql("update api_keys set revoked_at = ? where id = ? and revoked_at is null")
                .params(Timestamp.from(clock.instant()), keyId)
                .update();
        if (updated == 0 && jdbc.sql("select count(*) from api_keys where id = ?").param(keyId)
                .query(Long.class).single() == 0) {
            throw new ApiKeyNotFoundException(keyId);
        }
    }

    /** Resolves a presented key to its merchant; empty for unknown or revoked keys. */
    public Optional<ApiKeyOwner> authenticate(String presentedKey) {
        if (presentedKey == null || !presentedKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        // Lookup by hash: the database never sees the key, and the timing reveals nothing usable.
        return jdbc.sql("select id, merchant_id from api_keys where key_hash = ? and revoked_at is null")
                .param(hash(presentedKey))
                .query((rs, row) -> new ApiKeyOwner(rs.getString("merchant_id"), rs.getObject("id", UUID.class)))
                .optional();
    }

    private void requireMerchant(String merchantId) {
        if (jdbc.sql("select count(*) from merchants where id = ?").param(merchantId).query(Long.class).single() == 0) {
            throw new MerchantNotFoundException(merchantId);
        }
    }

    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record NewMerchant(String merchantId, String name, IssuedApiKey apiKey) {
    }

    /** {@code key} is the full secret; it is returned once, at creation, and never again. */
    public record IssuedApiKey(UUID id, String key, String keyPrefix) {
    }

    public record ApiKeySummary(UUID id, String keyPrefix, Instant createdAt, Instant revokedAt) {
    }

    public record ApiKeyOwner(String merchantId, UUID apiKeyId) {
    }
}
