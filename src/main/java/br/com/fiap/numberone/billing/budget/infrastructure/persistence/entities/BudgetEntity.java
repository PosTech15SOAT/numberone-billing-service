package br.com.fiap.numberone.billing.budget.infrastructure.persistence.entities;

import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetApprovalOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "budget", uniqueConstraints = @UniqueConstraint(
        name = "uk_budget_service_order_version", columnNames = {"service_order_id", "budget_version"}))
public class BudgetEntity {

    @Id
    @Column(name = "budget_id", nullable = false, updatable = false)
    private UUID budgetId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Column(name = "service_order_id", nullable = false, updatable = false)
    private UUID serviceOrderId;

    @Column(name = "budget_version", nullable = false, updatable = false)
    private int budgetVersion;

    @Column(name = "quoted_amount", nullable = false, columnDefinition = "numeric")
    private BigDecimal quotedAmount;

    @Column(name = "commercial_snapshot", columnDefinition = "jsonb", updatable = false)
    @ColumnTransformer(write = "cast(? as jsonb)")
    private String commercialSnapshot;

    @Column(name = "approved_amount", columnDefinition = "numeric")
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BudgetStatus status;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_origin", length = 20)
    private BudgetApprovalOrigin approvalOrigin;

    @Column(name = "approved_by_employee_id", length = 255)
    private String approvedByEmployeeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected BudgetEntity() {
    }

    public BudgetEntity(UUID budgetId, UUID serviceOrderId, int budgetVersion,
                        BigDecimal quotedAmount, BigDecimal approvedAmount, BudgetStatus status,
                        LocalDateTime sentAt, LocalDateTime approvedAt,
                        LocalDateTime createdAt, LocalDateTime updatedAt, String commercialSnapshot) {
        this(budgetId, serviceOrderId, budgetVersion, quotedAmount, approvedAmount, status, sentAt, approvedAt,
                createdAt, updatedAt, commercialSnapshot, null, null);
    }

    public BudgetEntity(UUID budgetId, UUID serviceOrderId, int budgetVersion,
                        BigDecimal quotedAmount, BigDecimal approvedAmount, BudgetStatus status,
                        LocalDateTime sentAt, LocalDateTime approvedAt,
                        LocalDateTime createdAt, LocalDateTime updatedAt, String commercialSnapshot,
                        BudgetApprovalOrigin approvalOrigin, String approvedByEmployeeId) {
        this.approvalOrigin = approvalOrigin;
        this.approvedByEmployeeId = approvedByEmployeeId;
        this.budgetId = budgetId;
        this.serviceOrderId = serviceOrderId;
        this.budgetVersion = budgetVersion;
        this.quotedAmount = quotedAmount;
        this.commercialSnapshot = commercialSnapshot;
        this.approvedAmount = approvedAmount;
        this.status = status;
        this.sentAt = sentAt;
        this.approvedAt = approvedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void updateState(BigDecimal approvedAmount, BudgetStatus status, LocalDateTime sentAt,
                            LocalDateTime approvedAt, LocalDateTime updatedAt,
                            BudgetApprovalOrigin approvalOrigin, String approvedByEmployeeId) {
        this.approvalOrigin = approvalOrigin;
        this.approvedByEmployeeId = approvedByEmployeeId;
        this.approvedAmount = approvedAmount;
        this.status = status;
        this.sentAt = sentAt;
        this.approvedAt = approvedAt;
        this.updatedAt = updatedAt;
    }

    public BudgetApprovalOrigin getApprovalOrigin() { return approvalOrigin; }
    public String getApprovedByEmployeeId() { return approvedByEmployeeId; }
    public Long getRowVersion() { return rowVersion; }
    public UUID getBudgetId() { return budgetId; }
    public UUID getServiceOrderId() { return serviceOrderId; }
    public int getBudgetVersion() { return budgetVersion; }
    public BigDecimal getQuotedAmount() { return quotedAmount; }
    public String getCommercialSnapshot() { return commercialSnapshot; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public BudgetStatus getStatus() { return status; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
