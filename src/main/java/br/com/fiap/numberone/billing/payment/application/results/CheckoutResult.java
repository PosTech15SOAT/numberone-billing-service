package br.com.fiap.numberone.billing.payment.application.results;

import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;

import java.util.UUID;

public record CheckoutResult(UUID chargeId, UUID attemptId, String providerOrderId,
                             String checkoutUrl, PaymentAttemptStatus status) {
    public static CheckoutResult from(Charge charge, UUID attemptId) {
        PaymentAttempt attempt = charge.getAttempts().stream()
                .filter(candidate -> candidate.getAttemptId().equals(attemptId)).findFirst().orElseThrow();
        return new CheckoutResult(charge.getChargeId(), attemptId, attempt.getProviderOrderId(),
                attempt.getCheckoutUrl(), attempt.getStatus());
    }

    @Override
    public String toString() {
        return "CheckoutResult[chargeId=" + chargeId + ", attemptId=" + attemptId + ", status=" + status + "]";
    }
}
