package dev.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.payflow.payment.merchant.MerchantService;
import dev.payflow.payment.merchant.MerchantService.NewMerchant;
import dev.payflow.payment.ratelimit.RateLimitDecision;
import dev.payflow.payment.ratelimit.RedisTokenBucketRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Per-merchant token bucket in Redis. Refill is ~1 token per 100 s, so buckets do not refill mid-test. */
@SpringBootTest(properties = {
        "payflow.outbox.poll-interval=1h",
        "payflow.reconciliation.interval=1h",
        "payflow.rate-limit.capacity=5",
        "payflow.rate-limit.refill-per-second=0.01"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RateLimitIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    MerchantService merchants;

    @Test
    void burstIsAllowedThenRejectedWithRetryAfter() throws Exception {
        String auth = newMerchantAuth();
        for (int remaining = 4; remaining >= 0; remaining--) {
            balances(auth).andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "5"))
                    .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(remaining)));
        }
        balances(auth).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(header().string("Retry-After", "100"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    @Test
    void eachMerchantHasItsOwnBucket() throws Exception {
        String busy = newMerchantAuth();
        String quiet = newMerchantAuth();
        for (int i = 0; i < 5; i++) {
            balances(busy).andExpect(status().isOk());
        }
        balances(busy).andExpect(status().isTooManyRequests());
        balances(quiet).andExpect(status().isOk());
    }

    @Test
    void allKeysOfAMerchantShareOneBucket() throws Exception {
        NewMerchant merchant = merchants.createMerchant("two keys");
        String first = "Bearer " + merchant.apiKey().key();
        String second = "Bearer " + merchants.issueKey(merchant.merchantId()).key();
        for (int i = 0; i < 5; i++) {
            balances(first).andExpect(status().isOk());
        }
        balances(second).andExpect(status().isTooManyRequests());
    }

    @Test
    void concurrentRequestsCannotOverspendTheBucket() throws Exception {
        String auth = newMerchantAuth();
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return balances(auth).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        }
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(5);
        assertThat(statuses.stream().filter(s -> s == 429)).hasSize(15);
    }

    @Test
    void unreachableRedisFailsOpen() {
        // Nothing listens on port 1; the limiter must allow the request rather than fail it.
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(java.time.Duration.ofMillis(250)).build());
        factory.afterPropertiesSet();
        factory.start();
        try {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            var limiter = new RedisTokenBucketRateLimiter(new StringRedisTemplate(factory), meters, 5, 1);

            RateLimitDecision decision = limiter.tryAcquire("ratelimit:merchant:any");

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.enforced()).isFalse();
            assertThat(meters.counter("payflow.ratelimit.decisions", "result", "unavailable").count()).isEqualTo(1);
        } finally {
            factory.destroy();
        }
    }

    private String newMerchantAuth() {
        return "Bearer " + merchants.createMerchant("rate limited").apiKey().key();
    }

    private ResultActions balances(String auth) throws Exception {
        return mvc.perform(get("/v1/balances").header("Authorization", auth));
    }
}
