package br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Identidade e estado da OS no CT-02A. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceOrderPaymentResponse(String serviceOrderId, String status) {
    @Override
    public String toString() { return "ServiceOrderPaymentResponse[details=REDACTED]"; }
}
