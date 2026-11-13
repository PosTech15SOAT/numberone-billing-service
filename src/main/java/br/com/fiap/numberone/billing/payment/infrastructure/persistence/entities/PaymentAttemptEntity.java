package br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities;

import br.com.fiap.numberone.billing.payment.domain.enums.PaymentAttemptStatus;
import br.com.fiap.numberone.billing.payment.domain.enums.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempt")
public class PaymentAttemptEntity {
    @Id
    @Column(name = "attempt_id", nullable = false, updatable = false)
    private UUID attemptId;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 10)
    private PaymentMethod paymentMethod;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PaymentAttemptStatus status;

    @Column(name = "provider_payment_id", nullable = true, length = 255)
    private String providerPaymentId;

    @Column(name = "provider_order_id", length = 255)
    private String providerOrderId;

    @Column(name = "checkout_url", length = 2048)
    private String checkoutUrl;

    @Column(name = "checkout_creation_claimed_at")
    private Instant checkoutCreationClaimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentAttemptEntity() {
    }

    public PaymentAttemptEntity(UUID attemptId, UUID chargeId, PaymentMethod paymentMethod, UUID idempotencyKey,
                                PaymentAttemptStatus status, String providerPaymentId, String providerOrderId,
                                String checkoutUrl, Instant checkoutCreationClaimedAt, Instant createdAt, Instant updatedAt) {
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

    public void updateState(PaymentAttemptStatus status, String providerPaymentId, String providerOrderId,
                            String checkoutUrl, PaymentMethod paymentMethod, Instant checkoutCreationClaimedAt, Instant updatedAt) {
        this.status = status;
        this.providerPaymentId = providerPaymentId;
        this.providerOrderId = providerOrderId;
        this.checkoutUrl = checkoutUrl;
        this.paymentMethod = paymentMethod;
        this.checkoutCreationClaimedAt = checkoutCreationClaimedAt;
        this.updatedAt = updatedAt;
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
