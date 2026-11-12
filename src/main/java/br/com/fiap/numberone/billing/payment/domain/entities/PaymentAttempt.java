package br.com.fiap.numberone.billing.payment.domain.entities;

import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentMethod;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentDataException;
import br.com.fiap.numberone.billing.payment.domain.exceptions.InvalidPaymentStateException;

import java.time.Instant;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.UUID;

public final class PaymentAttempt {
    private final UUID attemptId;
    private final UUID chargeId;
    private PaymentMethod paymentMethod;
    private final UUID idempotencyKey;
    private final Instant createdAt;
    private PaymentAttemptStatus status;
    private String providerPaymentId;
    private String providerOrderId;
    private String checkoutUrl;
    private Instant checkoutCreationClaimedAt;
    private Instant updatedAt;

    private PaymentAttempt(UUID attemptId, UUID chargeId, PaymentMethod paymentMethod,
                           UUID idempotencyKey, PaymentAttemptStatus status, String providerPaymentId,
                           String providerOrderId, String checkoutUrl, Instant checkoutCreationClaimedAt,
                           Instant createdAt, Instant updatedAt) {
        if (attemptId == null || chargeId == null || idempotencyKey == null
                || status == null || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new InvalidPaymentDataException("Dados obrigatórios ou datas da tentativa inválidos");
        }
        if (providerPaymentId != null) {
            validateExternalIdentifier(providerPaymentId);
        }
        if (providerOrderId != null) {
            validateExternalIdentifier(providerOrderId);
        }
        if (checkoutUrl != null) {
            validateCheckoutUrl(checkoutUrl);
            if (providerOrderId == null) {
                throw new InvalidPaymentDataException("URL de checkout exige identificação da order");
            }
        }
        if (status == PaymentAttemptStatus.PAID && providerPaymentId == null) {
            throw new InvalidPaymentDataException("Quitação exige identificação da operação no provedor");
        }
        if (checkoutCreationClaimedAt != null && (checkoutCreationClaimedAt.isBefore(createdAt)
                || checkoutCreationClaimedAt.isAfter(updatedAt))) {
            throw new InvalidPaymentDataException("Data da reserva de checkout inválida");
        }
        this.attemptId = attemptId;
        this.chargeId = chargeId;
        this.paymentMethod = paymentMethod;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.providerPaymentId = providerPaymentId;
        this.providerOrderId = providerOrderId;
        this.checkoutUrl = checkoutUrl;
        this.checkoutCreationClaimedAt = checkoutCreationClaimedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static PaymentAttempt create(UUID chargeId, UUID idempotencyKey, Instant now) {
        return new PaymentAttempt(UUID.randomUUID(), chargeId, null, idempotencyKey,
                PaymentAttemptStatus.PENDING, null, null, null, null, now, now);
    }

    public static PaymentAttempt restore(UUID attemptId, UUID chargeId, PaymentMethod paymentMethod,
                                         UUID idempotencyKey, PaymentAttemptStatus status, String providerPaymentId,
                                         Instant createdAt, Instant updatedAt) {
        return restore(attemptId, chargeId, paymentMethod, idempotencyKey, status, providerPaymentId,
                null, null, createdAt, updatedAt);
    }

    public static PaymentAttempt restore(UUID attemptId, UUID chargeId, PaymentMethod paymentMethod,
                                         UUID idempotencyKey, PaymentAttemptStatus status, String providerPaymentId,
                                         String providerOrderId, String checkoutUrl, Instant createdAt, Instant updatedAt) {
        return new PaymentAttempt(attemptId, chargeId, paymentMethod, idempotencyKey, status,
                providerPaymentId, providerOrderId, checkoutUrl, null, createdAt, updatedAt);
    }

    public static PaymentAttempt restore(UUID attemptId, UUID chargeId, PaymentMethod paymentMethod,
                                         UUID idempotencyKey, PaymentAttemptStatus status, String providerPaymentId,
                                         String providerOrderId, String checkoutUrl, Instant checkoutCreationClaimedAt,
                                         Instant createdAt, Instant updatedAt) {
        return new PaymentAttempt(attemptId, chargeId, paymentMethod, idempotencyKey, status,
                providerPaymentId, providerOrderId, checkoutUrl, checkoutCreationClaimedAt, createdAt, updatedAt);
    }

    boolean claimCheckoutCreation(Instant now) {
        if (checkoutCreationClaimedAt != null) {
            return false;
        }
        if (status != PaymentAttemptStatus.PENDING || providerOrderId != null || providerPaymentId != null) {
            throw new InvalidPaymentStateException("Tentativa não admite reserva de criação de checkout");
        }
        checkoutCreationClaimedAt = now;
        updatedAt = now;
        return true;
    }

    boolean associateProviderOrder(String confirmedOrderId, String confirmedCheckoutUrl, Instant now) {
        validateExternalIdentifier(confirmedOrderId);
        if (confirmedCheckoutUrl != null) {
            validateCheckoutUrl(confirmedCheckoutUrl);
        }
        if (providerOrderId != null && !providerOrderId.equals(confirmedOrderId)) {
            throw new InvalidPaymentStateException("Tentativa já vinculada a outra order do provedor");
        }
        if (checkoutUrl != null && confirmedCheckoutUrl != null && !checkoutUrl.equals(confirmedCheckoutUrl)) {
            throw new InvalidPaymentStateException("Tentativa já vinculada a outra URL de checkout");
        }
        String nextCheckoutUrl = confirmedCheckoutUrl == null ? checkoutUrl : confirmedCheckoutUrl;
        if (confirmedOrderId.equals(providerOrderId) && Objects.equals(nextCheckoutUrl, checkoutUrl)) {
            return false;
        }
        providerOrderId = confirmedOrderId;
        checkoutUrl = nextCheckoutUrl;
        if (status == PaymentAttemptStatus.UNKNOWN) {
            status = PaymentAttemptStatus.PENDING;
        }
        updatedAt = now;
        return true;
    }

    boolean recordPaymentMethod(PaymentMethod confirmedMethod, Instant now) {
        if (confirmedMethod == null) {
            throw new InvalidPaymentDataException("Meio de pagamento confirmado é obrigatório");
        }
        if (paymentMethod != null && paymentMethod != confirmedMethod) {
            throw new InvalidPaymentStateException("Tentativa já vinculada a outro meio de pagamento");
        }
        if (paymentMethod == confirmedMethod) {
            return false;
        }
        paymentMethod = confirmedMethod;
        updatedAt = now;
        return true;
    }

    boolean transitionTo(PaymentAttemptStatus nextStatus, String confirmedProviderPaymentId, Instant now) {
        if (confirmedProviderPaymentId != null) {
            validateExternalIdentifier(confirmedProviderPaymentId);
            if (providerPaymentId != null && !providerPaymentId.equals(confirmedProviderPaymentId)) {
                throw new InvalidPaymentStateException("Tentativa já vinculada a outra operação do provedor");
            }
        }
        if (nextStatus == PaymentAttemptStatus.PAID && confirmedProviderPaymentId == null) {
            throw new InvalidPaymentDataException("Quitação exige identificação da operação no provedor");
        }
        if (status == nextStatus && (confirmedProviderPaymentId == null
                || confirmedProviderPaymentId.equals(providerPaymentId))) {
            return false;
        }
        if (status != PaymentAttemptStatus.PENDING && status != PaymentAttemptStatus.UNKNOWN) {
            throw new InvalidPaymentStateException("Tentativa encerrada não admite outra transição");
        }
        status = nextStatus;
        if (confirmedProviderPaymentId != null) {
            providerPaymentId = confirmedProviderPaymentId;
        }
        updatedAt = now;
        return true;
    }

    private static void validateExternalIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 255
                || !identifier.equals(identifier.strip())
                || identifier.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidPaymentDataException("Identificação da operação no provedor inválida");
        }
    }

    private static void validateCheckoutUrl(String checkoutUrl) {
        if (checkoutUrl.isBlank() || checkoutUrl.length() > 2048) {
            throw new InvalidPaymentDataException("URL de checkout inválida");
        }
        try {
            URI checkoutUri = new URI(checkoutUrl);
            if (!"https".equalsIgnoreCase(checkoutUri.getScheme()) || checkoutUri.getHost() == null
                    || checkoutUri.getRawUserInfo() != null || checkoutUri.getRawFragment() != null
                    || checkoutUri.getPort() > 65535) {
                throw new InvalidPaymentDataException("URL de checkout deve ser HTTPS absoluta, sem credenciais ou fragmento");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidPaymentDataException("URL de checkout inválida", exception);
        }
    }

    public UUID getAttemptId() { return attemptId; }
    public UUID getChargeId() { return chargeId; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public PaymentAttemptStatus getStatus() { return status; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public Instant getCheckoutCreationClaimedAt() { return checkoutCreationClaimedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
