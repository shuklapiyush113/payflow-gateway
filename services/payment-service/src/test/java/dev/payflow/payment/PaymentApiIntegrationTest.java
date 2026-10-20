package dev.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.payflow.payment.TestcontainersConfiguration.RecordingEventPublisher;
import dev.payflow.payment.domain.PaymentRepository;
import dev.payflow.payment.ledger.LedgerEntry;
import dev.payflow.payment.ledger.LedgerEntryRepository;
import dev.payflow.payment.merchant.MerchantService;
import dev.payflow.payment.merchant.MerchantService.NewMerchant;
import dev.payflow.payment.outbox.OutboxRelay;
import dev.payflow.payment.outbox.OutboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "payflow.outbox.poll-interval=1h")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentApiIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    PaymentRepository payments;
    @Autowired
    LedgerEntryRepository ledgerEntries;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    OutboxRelay relay;
    @Autowired
    RecordingEventPublisher publisher;

    @Autowired
    MerchantService merchants;

    /** A fresh merchant and API key per test keeps tests independent without truncating tables. */
    String merchant;
    String auth;

    @BeforeEach
    void newMerchant() {
        NewMerchant created = merchants.createMerchant("test merchant");
        merchant = created.merchantId();
        auth = "Bearer " + created.apiKey().key();
    }

    @Test
    void createWithCaptureWritesBalancedLedger() throws Exception {
        JsonNode payment = body(createPayment(newKey(), 10_000, "tok_visa_4242", true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.source").value("tok_****4242"))
                .andExpect(header().string("Idempotent-Replayed", "false")));

        List<LedgerEntry> entries = ledgerEntries.findByPaymentId(id(payment));
        assertThat(entries).hasSize(3);
        assertBalanced(entries);
        assertThat(balances()).containsEntry("PSP_RECEIVABLE", 10_000L)
                .containsEntry("MERCHANT_PAYABLE", -9_800L)
                .containsEntry("FEE_REVENUE", -200L);
    }

    @Test
    void retryWithSameKeyReturnsSamePaymentAndChargesOnce() throws Exception {
        String key = newKey();
        JsonNode first = body(createPayment(key, 5_000, "tok_visa_4242", true).andExpect(status().isCreated()));
        JsonNode retry = body(createPayment(key, 5_000, "tok_visa_4242", true)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));

        assertThat(retry.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(ledgerEntries.findByPaymentId(id(first))).hasSize(3);
        assertThat(balances()).containsEntry("PSP_RECEIVABLE", 5_000L);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        String key = newKey();
        createPayment(key, 5_000, "tok_visa_4242", true).andExpect(status().isCreated());
        createPayment(key, 9_999, "tok_visa_4242", true)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOnePayment() throws Exception {
        String key = newKey();
        List<Integer> statuses = runConcurrently(8, () ->
                createPayment(key, 2_500, "tok_visa_4242", true).andReturn().getResponse().getStatus());

        assertThat(statuses).allMatch(s -> s == 201 || s == 409).contains(201);
        assertThat(balances()).containsEntry("PSP_RECEIVABLE", 2_500L);
    }

    @Test
    void concurrentCapturesOfOneAuthorizationCaptureOnce() throws Exception {
        UUID id = id(body(createPayment(newKey(), 7_000, "tok_visa_4242", false)
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))));

        List<Integer> statuses = runConcurrently(6, () -> mvc.perform(post("/v1/payments/{id}/capture", id)
                        .header("Authorization", auth)
                        .header("Idempotency-Key", newKey()))
                .andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(5);
        assertThat(ledgerEntries.findByPaymentId(id)).hasSize(3);
    }

    @Test
    void refundsAreCappedAndLedgerStaysBalanced() throws Exception {
        UUID id = id(body(createPayment(newKey(), 10_000, "alice@okbank", true)));
        assertThat(payments.findById(id)).get().extracting(p -> p.getSourceMasked()).isEqualTo("a****@okbank");

        refund(id, 3_000).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));
        refund(id, 7_001).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("refund_exceeds_capture"));
        refund(id, 7_000).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));

        assertBalanced(ledgerEntries.findByPaymentId(id));
        assertThat(balances()).containsEntry("PSP_RECEIVABLE", 0L)
                .containsEntry("MERCHANT_PAYABLE", 200L)
                .containsEntry("FEE_REVENUE", -200L);
    }

    @Test
    void bankDeclineAndRiskBlockAreRecordedWithoutLedgerEntries() throws Exception {
        JsonNode declined = body(createPayment(newKey(), 1_000, "tok_decline_card", true)
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.failureReason").value("insufficient_funds")));
        JsonNode blocked = body(createPayment(newKey(), 60_000_000, "tok_visa_4242", true)
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.failureReason").value("amount_above_risk_limit")));
        JsonNode failed = body(createPayment(newKey(), 1_000, "tok_unavailable", true)
                .andExpect(jsonPath("$.status").value("FAILED")));

        for (JsonNode payment : List.of(declined, blocked, failed)) {
            assertThat(ledgerEntries.findByPaymentId(id(payment))).isEmpty();
        }
    }

    @Test
    void invalidRequestsReturnProblemDetails() throws Exception {
        createPayment(newKey(), 1_000, "4242424242424242", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(post("/v1/payments").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/payments/{id}", UUID.randomUUID()).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantsCannotSeeEachOthersPayments() throws Exception {
        UUID id = id(body(createPayment(newKey(), 1_000, "tok_visa_4242", true)));
        String otherMerchant = "Bearer " + merchants.createMerchant("other").apiKey().key();
        mvc.perform(get("/v1/payments/{id}", id).header("Authorization", otherMerchant))
                .andExpect(status().isNotFound());
    }

    @Test
    void outboxRelayPublishesEventsInOrderOnce() throws Exception {
        UUID id = id(body(createPayment(newKey(), 4_000, "wal_8f3k2m", true)));
        refund(id, 1_000).andExpect(status().isOk());

        while (relay.relayBatch() > 0) {
            // drain everything, including events from other tests
        }
        List<String> types = publisher.published.stream()
                .filter(p -> p.key().equals(id.toString()))
                .map(RecordingEventPublisher.Published::eventType)
                .toList();
        assertThat(types).containsExactly("payment.authorized", "payment.captured", "payment.refunded");
        assertThat(outbox.findByAggregateIdOrderByCreatedAt(id.toString()))
                .allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());

        relay.relayBatch();
        assertThat(publisher.published.stream().filter(p -> p.key().equals(id.toString()))).hasSize(3);
    }

    private ResultActions createPayment(String key, long amount, String source, boolean capture) throws Exception {
        String method = source.startsWith("tok_") ? "CARD" : source.startsWith("wal_") ? "WALLET" : "UPI";
        Map<String, Object> request = Map.of("amountMinor", amount, "currency", "INR", "method", method,
                "source", source, "capture", capture);
        return mvc.perform(post("/v1/payments")
                .header("Authorization", auth)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    private ResultActions refund(UUID id, long amount) throws Exception {
        return mvc.perform(post("/v1/payments/{id}/refunds", id)
                .header("Authorization", auth)
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":" + amount + "}"));
    }

    private Map<String, Long> balances() throws Exception {
        JsonNode rows = body(mvc.perform(get("/v1/balances").header("Authorization", auth)));
        Map<String, Long> balances = new java.util.HashMap<>();
        rows.forEach(r -> balances.put(r.get("account").asString(), r.get("balanceMinor").asLong()));
        return balances;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static UUID id(JsonNode payment) {
        return UUID.fromString(payment.get("id").asString());
    }

    private static String newKey() {
        return "idem_" + UUID.randomUUID();
    }

    private static void assertBalanced(List<LedgerEntry> entries) {
        Map<String, Long> totals = entries.stream().collect(Collectors.groupingBy(
                e -> e.getDirection().name(), Collectors.summingLong(LedgerEntry::getAmountMinor)));
        assertThat(totals.get("DEBIT")).isEqualTo(totals.get("CREDIT"));
    }

    /** Starts all tasks at the same moment to maximise contention. */
    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
