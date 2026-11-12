package br.com.fiap.numberone.billing.payment.application.results;

import java.math.BigDecimal;

public record ProviderPaymentSnapshot(String providerPaymentId, String status, String statusDetail,
                                      BigDecimal amount, BigDecimal paidAmount) {
}
