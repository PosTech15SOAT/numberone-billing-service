package br.com.fiap.numberone.billing.budget.application.gateways;

import br.com.fiap.numberone.billing.budget.domain.references.ServiceOrderQuotationContext;

import java.util.UUID;

public interface ServiceOrderGateway {

    ServiceOrderQuotationContext findByServiceOrderId(UUID serviceOrderId);
}
