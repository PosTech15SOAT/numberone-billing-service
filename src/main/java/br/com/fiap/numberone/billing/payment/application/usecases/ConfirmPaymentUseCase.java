package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;

public class ConfirmPaymentUseCase {
    private final ChargeGateway charges;

    public ConfirmPaymentUseCase(ChargeGateway charges) { this.charges = charges; }

    public void execute(OrderObservation observation) {
        if (observation == null) throw new IllegalArgumentException("Observação financeira é obrigatória");
        // Estados não conclusivos não liberam a reserva nem encerram a tentativa automaticamente.
        if (observation.situation() == OrderObservation.Situation.APPROVED) charges.confirmPayment(observation);
    }
}
