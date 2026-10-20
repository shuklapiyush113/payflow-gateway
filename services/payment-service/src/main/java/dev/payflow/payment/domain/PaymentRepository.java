package dev.payflow.payment.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, String merchantId);

    Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);
}
