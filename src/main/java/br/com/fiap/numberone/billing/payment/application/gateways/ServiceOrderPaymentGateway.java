package br.com.fiap.numberone.billing.payment.application.gateways;

import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;

import java.util.UUID;

public interface ServiceOrderPaymentGateway {
    ServiceOrderPaymentContext findById(UUID serviceOrderId);
}
