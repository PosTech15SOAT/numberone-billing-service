package br.com.fiap.numberone.billing.payment.infrastructure.rest.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MercadoPagoOrderDetailsResponse(String id, String type, @JsonProperty("processing_mode") String processingMode,
        String status, @JsonProperty("status_detail") String statusDetail, @JsonProperty("total_amount") String totalAmount,
        @JsonProperty("total_paid_amount") String totalPaidAmount, String currency,
        @JsonProperty("external_reference") String externalReference, Transactions transactions) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transactions(List<Payment> payments) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(String id, String status, @JsonProperty("status_detail") String statusDetail,
            String amount, @JsonProperty("paid_amount") String paidAmount) { }

    @Override public String toString() { return "MercadoPagoOrderDetailsResponse[details=REDACTED]"; }
}
