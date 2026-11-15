package br.com.fiap.numberone.billing.payment.application.usecases;

import br.com.fiap.numberone.billing.payment.application.exceptions.ChargeNotFoundException;
import br.com.fiap.numberone.billing.payment.application.gateways.ChargeGateway;
import br.com.fiap.numberone.billing.payment.application.results.ChargeCheckoutResult;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;

import java.util.Comparator;
import java.util.UUID;

public class GetChargeCheckoutUseCase {
    private final ChargeGateway charges;

    public GetChargeCheckoutUseCase(ChargeGateway charges) { this.charges = charges; }

    public ChargeCheckoutResult execute(UUID serviceOrderId) {
        if (serviceOrderId == null) throw new InvalidPaymentDataException("Identidade da OS é obrigatória");
        Charge charge = charges.findByServiceOrderId(serviceOrderId).orElseThrow(ChargeNotFoundException::new);
        CheckoutResult checkout = charge.getAttempts().stream()
                .max(Comparator.comparing(PaymentAttempt::getCreatedAt).thenComparing(PaymentAttempt::getAttemptId))
                .map(attempt -> CheckoutResult.from(charge, attempt.getAttemptId())).orElse(null);
        return new ChargeCheckoutResult(charge.getChargeId(), charge.getServiceOrderId(), charge.getAmount(),
                charge.getCurrency().getCurrencyCode(), charge.getStatus().name(), checkout);
    }
}
