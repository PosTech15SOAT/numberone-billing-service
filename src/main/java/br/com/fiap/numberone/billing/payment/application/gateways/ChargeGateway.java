package br.com.fiap.numberone.billing.payment.application.gateways;

import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentMethod;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;
import br.com.fiap.numberone.billing.payment.application.results.PreparedCheckout;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;

import br.com.fiap.numberone.billing.payment.application.results.OrderObservation;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

public interface ChargeGateway {
    /** Verifica chave/orçamento e consulta replay sem escrever; retorna após commit e recusa transação chamadora. */
    Optional<CheckoutResult> inspectCheckout(UUID serviceOrderId, UUID idempotencyKey);
    /** Confirma a reserva antes de retornar; recusa transação chamadora. Replays nunca adquirem a reserva. */
    PreparedCheckout prepareCheckout(ServiceOrderPaymentContext serviceOrder, UUID idempotencyKey);
    Charge createCharge(ServiceOrderPaymentContext serviceOrder, UUID budgetId, Currency currency);
    Optional<Charge> findById(UUID chargeId);
    Optional<Charge> findByServiceOrderId(UUID serviceOrderId);
    Optional<Charge> findByProviderOrderId(String providerOrderId);
    PaymentAttempt startAttempt(UUID chargeId, UUID idempotencyKey);
    Charge associateProviderOrder(UUID chargeId, UUID attemptId, String providerOrderId, String checkoutUrl);
    Charge recordPaymentMethod(UUID chargeId, UUID attemptId, PaymentMethod paymentMethod);
    Charge markAttemptUnknown(UUID chargeId, UUID attemptId);
    Charge recordPendingAttempt(UUID chargeId, UUID attemptId, String providerPaymentId);
    Charge confirmAttemptFailure(UUID chargeId, UUID attemptId);
    Charge confirmAttemptExpiration(UUID chargeId, UUID attemptId);
    /** Revalida a evidência sob lock e persiste PAID + EV-03 no mesmo commit. */
    Charge confirmPayment(OrderObservation observation);
}
