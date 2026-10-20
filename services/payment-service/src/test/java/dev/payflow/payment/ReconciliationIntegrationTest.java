package dev.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.payflow.payment.application.PaymentReconciler;
import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.Payment;
import dev.payflow.payment.domain.PaymentMethod;
import dev.payflow.payment.domain.PaymentRepository;
import dev.payflow.payment.domain.PaymentStatus;
import dev.payflow.payment.ledger.LedgerEntryRepository;
import dev.payflow.payment.outbox.OutboxEvent;
import dev.payflow.payment.outbox.OutboxRepository;
import dev.payflow.payment.processor.MockBankConnector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Payments whose bank outcome was lost (timeout or crash) and how the reconciler settles them. */
@SpringBootTest(properties = {
        "payflow.outbox.poll-interval=1h",
        "payflow.reconciliation.interval=1h",
        "payflow.reconciliation.stale-after=0s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReconciliationIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PaymentRepository payments;
    @Autowired
    LedgerEntryRepository ledgerEntries;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    PaymentReconciler reconciler;
    @Autowired
    MockBankConnector bank;

    String merchant;

    @BeforeEach
    void newMerchant() {
        merchant = "m_" + UUID.randomUUID();
    }

    @Test
    void timeoutThatTheBankApprovedIsCapturedByReconciliation() throws Exception {
        UUID id = id(body(createPayment(newKey(), 10_000, "tok_timeout_card", true)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/v1/payments/")))
                .andExpect(jsonPath("$.status").value("PENDING"))));
        assertThat(ledgerEntries.findByPaymentId(id)).isEmpty();

        reconciler.reconcileBatch();

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getBankReference()).startsWith("bank_");
        assertThat(ledgerEntries.findByPaymentId(id)).hasSize(3);
        assertThat(eventTypes(id)).containsExactly("payment.pending", "payment.authorized", "payment.captured");

        // Settled payments are not picked up again.
        reconciler.reconcileBatch();
        assertThat(ledgerEntries.findByPaymentId(id)).hasSize(3);
        assertThat(eventTypes(id)).hasSize(3);
    }

    @Test
    void requestTheBankNeverReceivedIsFailed() throws Exception {
        UUID id = id(body(createPayment(newKey(), 10_000, "tok_lost_card", true)
                .andExpect(status().isAccepted())));

        reconciler.reconcileBatch();

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("not_received_by_bank");
        assertThat(ledgerEntries.findByPaymentId(id)).isEmpty();
    }

    @Test
    void crashAfterTheBankApprovedIsRecordedAsAuthorized() {
        // The request saved the payment and the bank approved it, then the instance died.
        Payment stuck = payments.save(new Payment(UUID.randomUUID(), merchant, newKey(), Money.of(2_000, "INR"),
                PaymentMethod.UPI, "a****@okbank", false, null, Instant.now()));
        bank.authorize(stuck.getId(), PaymentMethod.UPI, "alice@okbank", Money.of(2_000, "INR"));

        reconciler.reconcileBatch();

        Payment payment = payments.findById(stuck.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getRiskScore()).isNull();
        assertThat(ledgerEntries.findByPaymentId(stuck.getId())).isEmpty();
        assertThat(eventTypes(stuck.getId())).containsExactly("payment.authorized");
    }

    @Test
    void lostIdempotentResponseDoesNotCreateASecondPayment() throws Exception {
        String key = newKey();
        UUID id = id(body(createPayment(key, 5_000, "tok_visa_4242", true).andExpect(status().isCreated())));

        // Simulate a crash after the payment committed but before the response was stored.
        jdbc.sql("""
                        update idempotency_keys
                        set status = 'IN_PROGRESS', response_body = null, completed_at = null,
                            created_at = now() - interval '5 minutes'
                        where merchant_id = ? and idem_key = ?
                        """)
                .params(merchant, key)
                .update();

        UUID retried = id(body(createPayment(key, 5_000, "tok_visa_4242", true)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))));

        assertThat(retried).isEqualTo(id);
        assertThat(jdbc.sql("select count(*) from payments where merchant_id = ?").param(merchant)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(ledgerEntries.findByPaymentId(id)).hasSize(3);
    }

    @Test
    void concurrentReconcilersSettleEachPaymentOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(id(body(createPayment(newKey(), 1_000 + i, "tok_timeout_card", true)
                    .andExpect(status().isAccepted()))));
        }

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return reconciler.reconcileBatch();
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                run.get();
            }
        }

        for (UUID id : ids) {
            assertThat(payments.findById(id)).get().extracting(Payment::getStatus).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(ledgerEntries.findByPaymentId(id)).hasSize(3);
            assertThat(eventTypes(id)).containsExactly("payment.pending", "payment.authorized", "payment.captured");
        }
    }

    private List<String> eventTypes(UUID paymentId) {
        return outbox.findByAggregateIdOrderByCreatedAt(paymentId.toString()).stream()
                .map(OutboxEvent::getEventType)
                .toList();
    }

    private ResultActions createPayment(String key, long amount, String source, boolean capture) throws Exception {
        String method = source.startsWith("tok_") ? "CARD" : source.startsWith("wal_") ? "WALLET" : "UPI";
        Map<String, Object> request = Map.of("amountMinor", amount, "currency", "INR", "method", method,
                "source", source, "capture", capture);
        return mvc.perform(post("/v1/payments")
                .header("X-Merchant-Id", merchant)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
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
}
