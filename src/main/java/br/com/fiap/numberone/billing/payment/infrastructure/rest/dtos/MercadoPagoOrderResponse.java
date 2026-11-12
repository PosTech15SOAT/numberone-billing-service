package br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MercadoPagoOrderResponse(String id, @JsonProperty("checkout_url") String checkoutUrl, String status,
                                      String currency, @JsonProperty("total_amount") String totalAmount,
                                      @JsonProperty("external_reference") String externalReference) {
    @Override
    public String toString() { return "MercadoPagoOrderResponse[details=REDACTED]"; }
}
