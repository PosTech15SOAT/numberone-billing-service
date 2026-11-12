package br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** PROPOSTA CT-02A do HTML; campos adicionais não são necessários ao consumidor. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceOrderPaymentResponse(String serviceOrderId, String status) {
    @Override
    public String toString() { return "ServiceOrderPaymentResponse[details=REDACTED]"; }
}
