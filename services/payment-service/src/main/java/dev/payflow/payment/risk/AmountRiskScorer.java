package dev.payflow.payment.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import dev.payflow.payment.domain.Money;
import dev.payflow.payment.domain.PaymentMethod;

/** Placeholder scorer: risk grows with amount and payments above the limit are blocked. */
@Component
public class AmountRiskScorer implements RiskScorer {

    private final Properties properties;

    public AmountRiskScorer(Properties properties) {
        this.properties = properties;
    }

    @Override
    public RiskAssessment assess(String merchantId, PaymentMethod method, String source, Money amount) {
        double score = Math.min(1.0, (double) amount.minor() / properties.blockAboveMinor());
        if (amount.minor() > properties.blockAboveMinor()) {
            return RiskAssessment.block(score, "amount_above_risk_limit");
        }
        return RiskAssessment.allow(score);
    }

    /** {@code payflow.risk.block-above-minor}: block payments above this many minor units. */
    @ConfigurationProperties("payflow.risk")
    public record Properties(long blockAboveMinor) {
    }
}
