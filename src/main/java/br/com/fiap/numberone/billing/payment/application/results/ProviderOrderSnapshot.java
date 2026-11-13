package br.com.fiap.numberone.billing.payment.application.results;

import java.math.BigDecimal;
import java.util.List;

public record ProviderOrderSnapshot(String providerOrderId, String status, String statusDetail,
                                   BigDecimal totalAmount, BigDecimal totalPaidAmount, String currency,
                                   String externalReference, List<ProviderPaymentSnapshot> payments,
                                   boolean hasRefundsOrChargebacks) {
    public ProviderOrderSnapshot { payments = List.copyOf(payments); }
}
