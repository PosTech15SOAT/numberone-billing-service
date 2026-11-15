package br.com.fiap.numberone.billing.payment.support;

import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;
import br.com.fiap.numberone.billing.payment.application.results.ProviderPaymentSnapshot;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;

import java.util.List;
import java.util.UUID;

public final class PaymentConfirmationFixtures {
    private PaymentConfirmationFixtures() { }
    public static OrderObservation approved(Charge charge, UUID attemptId, String paymentId) {
        PaymentAttempt attempt = charge.getAttempts().stream().filter(candidate -> candidate.getAttemptId().equals(attemptId)).findFirst().orElseThrow();
        return new OrderObservation(attempt.getProviderOrderId(), charge.getChargeId(), attemptId, charge.getServiceOrderId(),
                OrderObservation.Situation.APPROVED, new ProviderOrderSnapshot(attempt.getProviderOrderId(), "processed", "accredited",
                charge.getAmount(), charge.getAmount(), "BRL", attemptId.toString(),
                List.of(new ProviderPaymentSnapshot(paymentId, "processed", "accredited", charge.getAmount(), charge.getAmount())), false));
    }
}
