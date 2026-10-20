package dev.payflow.payment.risk;

/** Score in [0, 1]; higher is riskier. */
public record RiskAssessment(double score, boolean block, String reason) {

    public static RiskAssessment allow(double score) {
        return new RiskAssessment(score, false, null);
    }

    public static RiskAssessment block(double score, String reason) {
        return new RiskAssessment(score, true, reason);
    }
}
