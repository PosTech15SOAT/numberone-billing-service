package br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MercadoPagoOrderRequest(String type, @JsonProperty("processing_mode") String processingMode,
                                     @JsonProperty("total_amount") String totalAmount,
                                     @JsonProperty("external_reference") String externalReference, List<Item> items) {
    public record Item(String title, int quantity, @JsonProperty("unit_price") String unitPrice) {
    }

    @Override
    public String toString() { return "MercadoPagoOrderRequest[details=REDACTED]"; }
}
