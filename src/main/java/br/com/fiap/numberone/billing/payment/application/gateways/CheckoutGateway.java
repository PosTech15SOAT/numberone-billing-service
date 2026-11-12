package br.com.fiap.numberone.billing.payment.application.gateways;

import br.com.fiap.numberone.billing.payment.application.results.CheckoutOrder;
import br.com.fiap.numberone.billing.payment.domain.entities.Charge;

import java.util.UUID;

public interface CheckoutGateway {
    /** Valida fora de transação e antes da reserva. A operação retornada vincula a mesma credencial. */
    ValidatedCheckout validateSeller();

    @FunctionalInterface
    interface ValidatedCheckout {
        /** Uso único, somente pelo adquirente da reserva e após seu commit. Não consulta identidade novamente. */
        CheckoutOrder createCheckout(Charge charge, UUID attemptId);
    }
}
