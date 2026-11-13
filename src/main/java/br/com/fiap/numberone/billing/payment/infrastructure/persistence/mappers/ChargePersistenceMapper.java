package br.com.fiap.numberone.billing.payment.infrastructure.persistence.mappers;

import br.com.fiap.numberone.billing.payment.domain.entities.Charge;
import br.com.fiap.numberone.billing.payment.domain.entities.PaymentAttempt;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities.ChargeEntity;
import br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities.PaymentAttemptEntity;
import org.springframework.stereotype.Component;

import java.util.Currency;
import java.util.List;

@Component
public class ChargePersistenceMapper {
    public Charge toDomain(ChargeEntity entity, List<PaymentAttemptEntity> attempts) {
        return Charge.restore(entity.getChargeId(), entity.getServiceOrderId(), entity.getBudgetId(),
                entity.getAmount(), Currency.getInstance(entity.getCurrency()), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt(), attempts.stream().map(this::toDomain).toList());
    }

    public PaymentAttempt toDomain(PaymentAttemptEntity entity) {
        return PaymentAttempt.restore(entity.getAttemptId(), entity.getChargeId(), entity.getPaymentMethod(),
                entity.getIdempotencyKey(), entity.getStatus(), entity.getProviderPaymentId(),
                entity.getProviderOrderId(), entity.getCheckoutUrl(), entity.getCheckoutCreationClaimedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    public PaymentAttemptEntity toEntity(PaymentAttempt attempt) {
        return new PaymentAttemptEntity(attempt.getAttemptId(), attempt.getChargeId(), attempt.getPaymentMethod(),
                attempt.getIdempotencyKey(), attempt.getStatus(), attempt.getProviderPaymentId(),
                attempt.getProviderOrderId(), attempt.getCheckoutUrl(), attempt.getCheckoutCreationClaimedAt(),
                attempt.getCreatedAt(), attempt.getUpdatedAt());
    }

    public void updateState(Charge charge, ChargeEntity entity) {
        entity.updateState(charge.getStatus(), charge.getUpdatedAt());
    }

    public void updateState(PaymentAttempt attempt, PaymentAttemptEntity entity) {
        entity.updateState(attempt.getStatus(), attempt.getProviderPaymentId(), attempt.getProviderOrderId(),
                attempt.getCheckoutUrl(), attempt.getPaymentMethod(), attempt.getCheckoutCreationClaimedAt(), attempt.getUpdatedAt());
    }
}
