package br.com.fiap.numberone.billing.payment.application.gateways;

import br.com.fiap.numberone.billing.payment.application.results.ProviderOrderSnapshot;

/** Leitura do estado oficial; não oferece comandos de criação ou confirmação. */
public interface OrderLookupGateway {
    ProviderOrderSnapshot findById(String providerOrderId);
}
