package dev.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.payflow.payment.domain.PaymentRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API-key authentication, merchant scoping and the admin API. */
@SpringBootTest(properties = {
        "payflow.outbox.poll-interval=1h",
        "payflow.reconciliation.interval=1h",
        "payflow.admin.token=" + SecurityIntegrationTest.ADMIN_TOKEN})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

    static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdefghijklmnop";
    static final String ADMIN = "Bearer " + ADMIN_TOKEN;

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PaymentRepository payments;

    @Test
    void requestsWithoutAValidKeyAreRejected() throws Exception {
        mvc.perform(get("/v1/balances"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(get("/v1/balances").header("Authorization", "Bearer sk_not_a_real_key"))
                .andExpect(status().isUnauthorized());
        // The old trust-the-header scheme no longer works.
        mvc.perform(get("/v1/balances").header("X-Merchant-Id", "m_demo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void merchantComesFromTheKeyNotFromTheHeader() throws Exception {
        JsonNode alice = createMerchant("alice");
        JsonNode bob = createMerchant("bob");

        JsonNode payment = body(mvc.perform(post("/v1/payments")
                        .header("Authorization", bearer(alice))
                        .header("X-Merchant-Id", bob.get("merchantId").asString())
                        .header("Idempotency-Key", "idem_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":1000,"currency":"INR","method":"CARD","source":"tok_visa_4242","capture":true}
                                """))
                .andExpect(status().isCreated()));

        UUID id = UUID.fromString(payment.get("id").asString());
        assertThat(payments.findById(id)).get().extracting(p -> p.getMerchantId())
                .isEqualTo(alice.get("merchantId").asString());
        mvc.perform(get("/v1/payments/{id}", id).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void keysCanBeRotatedAndRevoked() throws Exception {
        JsonNode merchant = createMerchant("rotating");
        String merchantId = merchant.get("merchantId").asString();
        String oldKey = "Bearer " + merchant.get("apiKey").get("key").asString();

        JsonNode newKey = body(mvc.perform(post("/admin/merchants/{id}/api-keys", merchantId).header("Authorization", ADMIN))
                .andExpect(status().isCreated()));
        mvc.perform(get("/v1/balances").header("Authorization", "Bearer " + newKey.get("key").asString()))
                .andExpect(status().isOk());

        mvc.perform(delete("/admin/api-keys/{id}", merchant.get("apiKey").get("id").asString()).header("Authorization", ADMIN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/balances").header("Authorization", oldKey)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/balances").header("Authorization", "Bearer " + newKey.get("key").asString()))
                .andExpect(status().isOk());

        // Listing shows prefixes and revocation, never the key itself.
        mvc.perform(get("/admin/merchants/{id}/api-keys", merchantId).header("Authorization", ADMIN))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].revokedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].key").doesNotExist());
    }

    @Test
    void onlyTheKeyHashIsStored() throws Exception {
        String key = createMerchant("hashed").get("apiKey").get("key").asString();
        assertThat(key).startsWith("sk_").hasSize(46);
        assertThat(jdbc.sql("select count(*) from api_keys where key_hash = ? or key_prefix = ?")
                .params(key, key).query(Long.class).single()).isZero();
    }

    @Test
    void adminApiNeedsTheAdminToken() throws Exception {
        String merchantKey = bearer(createMerchant("not-an-admin"));
        mvc.perform(post("/admin/merchants").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/merchants").header("Authorization", merchantKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        // And the admin token is not a merchant key.
        mvc.perform(get("/v1/balances").header("Authorization", ADMIN)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/merchants/{id}/api-keys", "m_missing").header("Authorization", ADMIN))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthStaysOpenForProbes() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    private JsonNode createMerchant(String name) throws Exception {
        return body(mvc.perform(post("/admin/merchants").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()));
    }

    private static String bearer(JsonNode merchant) {
        return "Bearer " + merchant.get("apiKey").get("key").asString();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
