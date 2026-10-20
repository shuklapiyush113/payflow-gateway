package dev.payflow.payment.risk;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/**
 * Port to fraud scoring. Step 1 ships {@link AmountRiskScorer}; step 3 replaces it with a chain of
 * checks (velocity, blocklist, ML model served through ONNX Runtime).
 */
public interface RiskScorer {

    RiskAssessment assess(String merchantId, PaymentMethod method, String source, Money amount);
}
