package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutCreationException;
import br.com.fiap.numberone.billing.payment.application.exceptions.CheckoutOutcomeUnknownException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.CheckoutGateway;
import br.com.fiap.numberone.billing.payment.application.gateways.ServiceOrderPaymentGateway;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.application.results.PreparedCheckout;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;

import java.util.Optional;
import java.util.UUID;

public class CreateCheckoutUseCase {
    private final ServiceOrderPaymentGateway serviceOrderGateway;
    private final ChargeGateway chargeGateway;
    private final CheckoutGateway checkoutGateway;

    public CreateCheckoutUseCase(ServiceOrderPaymentGateway serviceOrderGateway, ChargeGateway chargeGateway,
                                 CheckoutGateway checkoutGateway) {
        this.serviceOrderGateway = serviceOrderGateway;
        this.chargeGateway = chargeGateway;
        this.checkoutGateway = checkoutGateway;
    }

    public CheckoutResult execute(UUID serviceOrderId, UUID idempotencyKey) {
        if (serviceOrderId == null || idempotencyKey == null) {
            throw new InvalidPaymentDataException("Identidade da OS e chave de idempotência são obrigatórias");
        }
        Optional<CheckoutResult> replay = chargeGateway.inspectCheckout(serviceOrderId, idempotencyKey);
        if (replay.isPresent()) return replay.get();
        ServiceOrderPaymentContext serviceOrder = serviceOrderGateway.findById(serviceOrderId);
        if (serviceOrder == null || !serviceOrderId.equals(serviceOrder.serviceOrderId())) {
            throw new InvalidPaymentDataException("Consulta da OS retornou identidade inconsistente");
        }
        serviceOrder.requireCompleted();
        // Falhas de identidade propagam antes de qualquer nova cobrança, tentativa ou reserva.
        CheckoutGateway.ValidatedCheckout validatedCheckout = checkoutGateway.validateSeller();
        PreparedCheckout prepared = chargeGateway.prepareCheckout(serviceOrder, idempotencyKey);
        if (!prepared.acquired()) {
            return CheckoutResult.from(prepared.charge(), prepared.attemptId());
        }
        CheckoutOrder order;
        try {
            order = validatedCheckout.createCheckout(prepared.charge(), prepared.attemptId());
        } catch (CheckoutCreationException exception) {
            // Recusa desta requisição não constitui evidência de falha financeira da tentativa.
            return CheckoutResult.from(recordUnknown(prepared, exception), prepared.attemptId());
        } catch (RuntimeException exception) {
            recordUnknown(prepared, exception);
            throw new CheckoutOutcomeUnknownException(prepared.charge().getChargeId(), prepared.attemptId(), exception);
        }
        try {
            Charge updated = chargeGateway.associateProviderOrder(prepared.charge().getChargeId(), prepared.attemptId(),
                    order.providerOrderId(), order.checkoutUrl().toString());
            return CheckoutResult.from(updated, prepared.attemptId());
        } catch (RuntimeException exception) {
            recordUnknown(prepared, exception);
            throw new CheckoutOutcomeUnknownException(prepared.charge().getChargeId(), prepared.attemptId(), exception);
        }
    }

    private Charge recordUnknown(PreparedCheckout prepared, RuntimeException originalFailure) {
        // TODO: SAGA - reconciliar a identidade original; nunca liberar ou refazer POST por tempo decorrido.
        try {
            return chargeGateway.markAttemptUnknown(prepared.charge().getChargeId(), prepared.attemptId());
        } catch (RuntimeException persistenceFailure) {
            CheckoutOutcomeUnknownException outcome = new CheckoutOutcomeUnknownException(
                    prepared.charge().getChargeId(), prepared.attemptId(), originalFailure);
            outcome.addSuppressed(persistenceFailure);
            throw outcome;
        }
    }
}
