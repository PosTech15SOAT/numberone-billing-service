package br.com.fiap.numberone.billing.payment.api.mappers;

import br.com.fiap.numberone.billing.payment.api.dtos.ChargeCheckoutResponse;
import br.com.fiap.numberone.billing.payment.api.dtos.CheckoutResponse;
import br.com.fiap.numberone.billing.payment.application.results.ChargeCheckoutResult;
import br.com.fiap.numberone.billing.payment.application.results.CheckoutResult;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class PaymentApiMapper {
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    public UUID requireServiceOrderId(String value) {
        if (value == null || !value.matches(UUID_FORMAT)) {
            throw new InvalidPaymentDataException("Identidade da OS deve ser um UUID válido");
        }
        return UUID.fromString(value);
    }

    public UUID requireIdempotencyKey(List<String> values) {
        if (values == null || values.size() != 1 || values.getFirst() == null
                || !values.getFirst().matches(UUID_FORMAT)) {
            throw new InvalidPaymentDataException("Informe uma única chave UUID válida no header Idempotency-Key");
        }
        return UUID.fromString(values.getFirst());
    }

    public CheckoutResponse toResponse(CheckoutResult result) {
        String situation = switch (result.status()) {
            case UNKNOWN -> "RESULTADO_DESCONHECIDO";
            case PENDING -> result.checkoutUrl() == null ? "EM_PROCESSAMENTO" : "DISPONIVEL";
            case FAILED, EXPIRED, PAID -> "ENCERRADO";
        };
        return new CheckoutResponse(result.chargeId(), result.attemptId(), result.providerOrderId(),
                result.checkoutUrl(), result.status().name(), situation);
    }

    public ChargeCheckoutResponse toResponse(ChargeCheckoutResult result) {
        return new ChargeCheckoutResponse(result.chargeId(), result.serviceOrderId(), result.amount(), result.currency(),
                result.status(), result.checkout() == null ? null : toResponse(result.checkout()));
    }
}
