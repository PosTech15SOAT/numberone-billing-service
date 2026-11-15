package br.com.fiap.numberone.billing.payment.api.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargeCheckoutResponse(
        UUID idCobranca,
        UUID idOrdemServico,
        BigDecimal valor,
        String moeda,
        String status,
        CheckoutResponse checkout) {
}
