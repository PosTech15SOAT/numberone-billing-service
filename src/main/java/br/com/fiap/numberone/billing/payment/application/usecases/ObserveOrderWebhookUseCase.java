package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.exceptions.OrderLookupException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.OrderLookupGateway;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;
import br.com.fiap.numberone.billing.payment.application.services.OrderPaymentEvidence;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;

import java.util.Optional;

public class ObserveOrderWebhookUseCase {
    private final ChargeGateway charges;
    private final OrderLookupGateway orders;

    public ObserveOrderWebhookUseCase(ChargeGateway charges, OrderLookupGateway orders) {
        this.charges = charges;
        this.orders = orders;
    }

    /** Somente a entrada autenticada pode fornecer o identificador. Executar sem transação chamadora. */
    public OrderObservation execute(String providerOrderId) {
        if (providerOrderId == null || !providerOrderId.matches("[A-Za-z0-9_-]{1,255}")) throw inconsistent();
        Optional<Charge> existing = charges.findByProviderOrderId(providerOrderId);
        if (existing.isEmpty()) {
            return new OrderObservation(providerOrderId, null, null, null, OrderObservation.Situation.UNKNOWN_ORDER, null);
        }
        return OrderPaymentEvidence.observe(existing.get(), providerOrderId, orders.findById(providerOrderId));
    }

    private static OrderLookupException inconsistent() {
        return new OrderLookupException(OrderLookupException.Kind.INVALID_RESPONSE, null, null);
    }
}
