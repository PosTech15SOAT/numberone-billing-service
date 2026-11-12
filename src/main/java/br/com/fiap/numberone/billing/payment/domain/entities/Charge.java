package br.com.fiap.numberone.billing.payment.domain.entities;

import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentMethod;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;
import br.com.fiap.numberone.billing.payment.domain.references.ServiceOrderPaymentContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Charge {
    private final UUID chargeId;
    private final UUID serviceOrderId;
    private final UUID budgetId;
    private final BigDecimal amount;
    private final Currency currency;
    private final Instant createdAt;
    private final List<PaymentAttempt> attempts;
    private ChargeStatus status;
    private Instant updatedAt;

    private Charge(UUID chargeId, UUID serviceOrderId, UUID budgetId, BigDecimal amount, Currency currency,
                   ChargeStatus status, Instant createdAt, Instant updatedAt, List<PaymentAttempt> attempts) {
        if (chargeId == null || serviceOrderId == null || budgetId == null || amount == null
                || amount.signum() <= 0 || currency == null || status == null || createdAt == null
                || updatedAt == null || updatedAt.isBefore(createdAt) || attempts == null) {
            throw new InvalidPaymentDataException("Dados obrigatórios, valor ou datas da cobrança inválidos");
        }
        this.chargeId = chargeId;
        this.serviceOrderId = serviceOrderId;
        this.budgetId = budgetId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.attempts = new ArrayList<>();
        for (PaymentAttempt attempt : attempts) {
            if (attempt == null) {
                throw new InvalidPaymentDataException("Tentativa é obrigatória");
            }
            this.attempts.add(PaymentAttempt.restore(attempt.getAttemptId(), attempt.getChargeId(),
                    attempt.getPaymentMethod(), attempt.getIdempotencyKey(), attempt.getStatus(),
                    attempt.getProviderPaymentId(), attempt.getProviderOrderId(), attempt.getCheckoutUrl(),
                    attempt.getCheckoutCreationClaimedAt(), attempt.getCreatedAt(), attempt.getUpdatedAt()));
        }
        validateAttempts();
    }

    public static Charge create(ServiceOrderPaymentContext serviceOrder, UUID budgetId,
                                BigDecimal approvedAmount, Currency currency) {
        if (serviceOrder == null) {
            throw new InvalidPaymentDataException("Contexto da OS é obrigatório");
        }
        serviceOrder.requireCompleted();
        validateNewOperationAmount(approvedAmount, currency);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new Charge(UUID.randomUUID(), serviceOrder.serviceOrderId(), budgetId, approvedAmount,
                currency, ChargeStatus.OPEN, now, now, List.of());
    }

    public static Charge restore(UUID chargeId, UUID serviceOrderId, UUID budgetId, BigDecimal amount,
                                 Currency currency, ChargeStatus status, Instant createdAt, Instant updatedAt,
                                 List<PaymentAttempt> attempts) {
        return new Charge(chargeId, serviceOrderId, budgetId, amount, currency, status, createdAt, updatedAt, attempts);
    }

    public PaymentAttempt startAttempt(UUID idempotencyKey) {
        if (idempotencyKey == null) {
            throw new InvalidPaymentDataException("Chave de idempotência é obrigatória");
        }
        for (PaymentAttempt attempt : attempts) {
            if (attempt.getIdempotencyKey().equals(idempotencyKey)) {
                return attempt;
            }
        }
        if (status == ChargeStatus.PAID || attempts.stream().anyMatch(attempt -> attempt.getStatus().blocksNewAttempt())) {
            throw new InvalidPaymentStateException("Cobrança quitada ou com tentativa ainda não resolvida");
        }
        requirePayableAmount();
        Instant now = nextUpdate();
        PaymentAttempt attempt = PaymentAttempt.create(chargeId, idempotencyKey, now);
        attempts.add(attempt);
        updatedAt = now;
        return attempt;
    }

    public boolean claimCheckoutCreation(UUID attemptId) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        if (!attempt.claimCheckoutCreation(nextUpdate())) {
            return false;
        }
        updatedAt = attempt.getUpdatedAt();
        return true;
    }

    public void associateProviderOrder(UUID attemptId, String providerOrderId, String checkoutUrl) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        if (providerOrderId != null && attempts.stream().anyMatch(candidate -> candidate != attempt
                && providerOrderId.equals(candidate.getProviderOrderId()))) {
            throw new InvalidPaymentStateException("Order do provedor já vinculada a outra tentativa");
        }
        if (attempt.associateProviderOrder(providerOrderId, checkoutUrl, nextUpdate())) {
            updatedAt = attempt.getUpdatedAt();
        }
    }

    public void recordPaymentMethod(UUID attemptId, PaymentMethod paymentMethod) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        if (attempt.recordPaymentMethod(paymentMethod, nextUpdate())) {
            updatedAt = attempt.getUpdatedAt();
        }
    }

    public void markAttemptUnknown(UUID attemptId) {
        transition(attemptId, PaymentAttemptStatus.UNKNOWN, null);
    }

    public void recordPendingAttempt(UUID attemptId, String providerPaymentId) {
        if (providerPaymentId == null) {
            throw new InvalidPaymentDataException("Identificação da operação no provedor é obrigatória");
        }
        transition(attemptId, PaymentAttemptStatus.PENDING, providerPaymentId);
    }

    public void confirmAttemptFailure(UUID attemptId) {
        transition(attemptId, PaymentAttemptStatus.FAILED, null);
    }

    public void confirmAttemptExpiration(UUID attemptId) {
        transition(attemptId, PaymentAttemptStatus.EXPIRED, null);
    }

    public void requirePayableAmount() {
        validateNewOperationAmount(amount, currency);
    }

    public void confirmPayment(UUID attemptId, String providerPaymentId) {
        requirePayableAmount();
        transition(attemptId, PaymentAttemptStatus.PAID, providerPaymentId);
    }

    private void transition(UUID attemptId, PaymentAttemptStatus nextStatus, String providerPaymentId) {
        PaymentAttempt attempt = requireAttempt(attemptId);
        if (status == ChargeStatus.PAID && attempt.getStatus() != PaymentAttemptStatus.PAID) {
            throw new InvalidPaymentStateException("Cobrança já quitada");
        }
        if (providerPaymentId != null && attempts.stream().anyMatch(candidate -> candidate != attempt
                && providerPaymentId.equals(candidate.getProviderPaymentId()))) {
            throw new InvalidPaymentStateException("Operação do provedor já vinculada a outra tentativa");
        }
        Instant now = nextUpdate();
        if (attempt.transitionTo(nextStatus, providerPaymentId, now)) {
            updatedAt = now;
            if (nextStatus == PaymentAttemptStatus.PAID) {
                status = ChargeStatus.PAID;
            }
        }
    }

    private PaymentAttempt requireAttempt(UUID attemptId) {
        return attempts.stream().filter(candidate -> candidate.getAttemptId().equals(attemptId))
                .findFirst().orElseThrow(() -> new InvalidPaymentDataException("Tentativa não pertence à cobrança"));
    }

    private static void validateNewOperationAmount(BigDecimal amount, Currency currency) {
        if (amount == null || amount.signum() <= 0
                || currency == null || !"BRL".equals(currency.getCurrencyCode())) {
            throw new InvalidPaymentDataException("Pagamento exige valor positivo em BRL com no máximo duas casas decimais");
        }
        try {
            amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new InvalidPaymentDataException("Pagamento exige valor positivo em BRL com no máximo duas casas decimais", exception);
        }
    }

    private Instant nextUpdate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    private void validateAttempts() {
        Set<UUID> attemptIds = new HashSet<>();
        Set<UUID> idempotencyKeys = new HashSet<>();
        Set<String> providerPaymentIds = new HashSet<>();
        Set<String> providerOrderIds = new HashSet<>();
        int blockingCount = 0;
        int paidCount = 0;
        for (PaymentAttempt attempt : attempts) {
            if (attempt == null || !chargeId.equals(attempt.getChargeId())
                    || !attemptIds.add(attempt.getAttemptId()) || !idempotencyKeys.add(attempt.getIdempotencyKey())
                    || (attempt.getProviderPaymentId() != null && !providerPaymentIds.add(attempt.getProviderPaymentId()))
                    || (attempt.getProviderOrderId() != null && !providerOrderIds.add(attempt.getProviderOrderId()))
                    || attempt.getCreatedAt().isBefore(createdAt) || attempt.getUpdatedAt().isAfter(updatedAt)) {
                throw new InvalidPaymentDataException("Histórico de tentativas inconsistente com a cobrança");
            }
            if (attempt.getStatus().blocksNewAttempt()) { blockingCount++; }
            if (attempt.getStatus() == PaymentAttemptStatus.PAID) { paidCount++; }
        }
        if (blockingCount > 1 || (status == ChargeStatus.PAID) != (paidCount == 1)) {
            throw new InvalidPaymentDataException("Estado da cobrança inconsistente com suas tentativas");
        }
    }

    public UUID getChargeId() { return chargeId; }
    public UUID getServiceOrderId() { return serviceOrderId; }
    public UUID getBudgetId() { return budgetId; }
    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public ChargeStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<PaymentAttempt> getAttempts() { return List.copyOf(attempts); }
}
