package br.com.fiap.numberone.billing.payment.application.results;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargeCheckoutResult(UUID chargeId, UUID serviceOrderId, BigDecimal amount, String currency,
                                   String status, CheckoutResult checkout) {
}
