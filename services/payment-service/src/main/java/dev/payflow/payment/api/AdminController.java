package dev.payflow.payment.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.payflow.payment.merchant.MerchantService;
import dev.payflow.payment.merchant.MerchantService.ApiKeySummary;
import dev.payflow.payment.merchant.MerchantService.IssuedApiKey;
import dev.payflow.payment.merchant.MerchantService.NewMerchant;

/**
 * Operator API for onboarding merchants and managing their keys. Needs the admin token, and the k3s
 * ingress only routes {@code /v1}, so it is reachable through {@code kubectl port-forward} only.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final MerchantService merchants;

    public AdminController(MerchantService merchants) {
        this.merchants = merchants;
    }

    @PostMapping("/merchants")
    public ResponseEntity<NewMerchant> createMerchant(@Valid @RequestBody CreateMerchantRequest request) {
        NewMerchant created = merchants.createMerchant(request.name());
        return ResponseEntity.created(URI.create("/admin/merchants/" + created.merchantId())).body(created);
    }

    @PostMapping("/merchants/{merchantId}/api-keys")
    public ResponseEntity<IssuedApiKey> issueKey(@PathVariable String merchantId) {
        return ResponseEntity.status(201).body(merchants.issueKey(merchantId));
    }

    @GetMapping("/merchants/{merchantId}/api-keys")
    public List<ApiKeySummary> listKeys(@PathVariable String merchantId) {
        return merchants.listKeys(merchantId);
    }

    @DeleteMapping("/api-keys/{keyId}")
    public ResponseEntity<Void> revokeKey(@PathVariable UUID keyId) {
        merchants.revokeKey(keyId);
        return ResponseEntity.noContent().build();
    }

    public record CreateMerchantRequest(@NotBlank @Size(max = 128) String name) {
    }
}
