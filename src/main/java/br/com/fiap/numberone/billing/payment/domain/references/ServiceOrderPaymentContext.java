package br.com.fiap.numberone.billing.payment.domain.references;

import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.ServiceOrderNotCompletedException;

import java.util.UUID;

/** Snapshot confiável da OS; a consulta externa pertence à futura entrada do pagamento. */
public record ServiceOrderPaymentContext(UUID serviceOrderId, String status) {
    public ServiceOrderPaymentContext {
        if (serviceOrderId == null || status == null || status.isBlank()) {
            throw new InvalidPaymentDataException("Referência e status da OS são obrigatórios");
        }
    }

    public void requireCompleted() {
        if (!"COMPLETED".equals(status)) {
            throw new ServiceOrderNotCompletedException();
        }
    }
}
