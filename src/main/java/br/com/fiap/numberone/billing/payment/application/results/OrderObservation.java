package br.com.fiap.numberone.billing.payment.application.results;

import java.util.UUID;

/** Observação do provedor, nunca uma decisão de quitação do Billing. */
public record OrderObservation(String providerOrderId, UUID chargeId, UUID attemptId, UUID serviceOrderId,
                               Situation situation, ProviderOrderSnapshot snapshot) {
    public enum Situation { UNKNOWN_ORDER, PENDING, APPROVED, REJECTED, CANCELED, REFUNDED, INDETERMINATE }
}
