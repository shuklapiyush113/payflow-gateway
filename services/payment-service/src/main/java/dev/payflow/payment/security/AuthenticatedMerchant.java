package dev.payflow.payment.security;

import java.util.UUID;

/** Principal for a request made with a merchant API key; controllers take it via {@code @AuthenticationPrincipal}. */
public record AuthenticatedMerchant(String merchantId, UUID apiKeyId) {
}
