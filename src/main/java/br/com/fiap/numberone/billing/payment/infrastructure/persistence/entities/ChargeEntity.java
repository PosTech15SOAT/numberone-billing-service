package br.com.fiap.numberone.billing.payment.infrastructure.persistence.entities;

import br.com.fiap.numberone.billing.payment.domain.enums.ChargeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "charge")
public class ChargeEntity {
    @Id
    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    @Column(name = "service_order_id", nullable = false, updatable = false)
    private UUID serviceOrderId;

    @Column(name = "budget_id", nullable = false, updatable = false)
    private UUID budgetId;

    @Column(name = "amount", nullable = false, updatable = false, columnDefinition = "numeric")
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ChargeStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected ChargeEntity() {
    }

    public void updateState(ChargeStatus status, Instant updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public UUID getChargeId() { return chargeId; }
    public UUID getServiceOrderId() { return serviceOrderId; }
    public UUID getBudgetId() { return budgetId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public ChargeStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getRowVersion() { return rowVersion; }
}
