package br.com.fiap.numberone.billing.budget.domain.entities;

import br.com.fiap.numberone.billing.budget.domain.enums.BudgetStatus;
import br.com.fiap.numberone.billing.budget.domain.enums.BudgetApprovalOrigin;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ApprovedBudgetExistsException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetDataException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.InvalidBudgetStatusException;
import br.com.fiap.numberone.billing.budget.domain.exceptions.ObsoleteBudgetRevisionException;
import br.com.fiap.numberone.billing.budget.domain.valueobjects.BudgetComposition;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public class Budget {

    private final UUID budgetId;
    private final UUID serviceOrderId;
    private final int budgetVersion;
    private final BigDecimal quotedAmount;
    private final BudgetComposition composition;
    private BigDecimal approvedAmount;
    private BudgetStatus status;
    private LocalDateTime sentAt;
    private LocalDateTime approvedAt;
    private BudgetApprovalOrigin approvalOrigin;
    private String approvedByEmployeeId;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Budget(UUID budgetId, UUID serviceOrderId, int budgetVersion,
                   BigDecimal quotedAmount, BigDecimal approvedAmount, BudgetStatus status,
                   LocalDateTime sentAt, LocalDateTime approvedAt,
                   LocalDateTime createdAt, LocalDateTime updatedAt, BudgetComposition composition,
                   BudgetApprovalOrigin approvalOrigin, String approvedByEmployeeId) {
        if (budgetId == null || serviceOrderId == null) {
            throw new InvalidBudgetDataException("Os identificadores do orçamento e da ordem de serviço são obrigatórios");
        }
        if (budgetVersion <= 0) {
            throw new InvalidBudgetDataException("A versão do orçamento deve ser positiva");
        }
        if (quotedAmount == null) {
            throw new InvalidBudgetDataException("O valor proposto do orçamento é obrigatório");
        }
        if (status == null || createdAt == null) {
            throw new InvalidBudgetDataException("O estado do orçamento e a data de criação são obrigatórios");
        }
        this.budgetId = budgetId;
        this.serviceOrderId = serviceOrderId;
        this.budgetVersion = budgetVersion;
        this.quotedAmount = quotedAmount;
        this.composition = composition;
        this.approvedAmount = approvedAmount;
        this.status = status;
        this.sentAt = sentAt;
        this.approvedAt = approvedAt;
        this.approvalOrigin = approvalOrigin;
        this.approvedByEmployeeId = approvedByEmployeeId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Budget createDraft(UUID serviceOrderId, int budgetVersion, BigDecimal quotedAmount,
                                     BudgetComposition composition) {
        if (composition == null) {
            throw new InvalidBudgetDataException("A composição é obrigatória para criar um orçamento");
        }
        BudgetComposition.validateQuotedAmount(quotedAmount);
        LocalDateTime now = LocalDateTime.now();
        return new Budget(UUID.randomUUID(), serviceOrderId, budgetVersion, quotedAmount,
                null, BudgetStatus.DRAFT, null, null, now, now, composition, null, null);
    }

    public static Budget restore(UUID budgetId, UUID serviceOrderId, int budgetVersion,
                                 BigDecimal quotedAmount, BigDecimal approvedAmount, BudgetStatus status,
                                 LocalDateTime sentAt, LocalDateTime approvedAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        return restore(budgetId, serviceOrderId, budgetVersion, quotedAmount, approvedAmount,
                status, sentAt, approvedAt, createdAt, updatedAt, null);
    }

    public static Budget restore(UUID budgetId, UUID serviceOrderId, int budgetVersion,
                                 BigDecimal quotedAmount, BigDecimal approvedAmount, BudgetStatus status,
                                 LocalDateTime sentAt, LocalDateTime approvedAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt, BudgetComposition composition) {
        return restore(budgetId, serviceOrderId, budgetVersion, quotedAmount, approvedAmount, status,
                sentAt, approvedAt, createdAt, updatedAt, composition, null, null);
    }

    public static Budget restore(UUID budgetId, UUID serviceOrderId, int budgetVersion,
                                 BigDecimal quotedAmount, BigDecimal approvedAmount, BudgetStatus status,
                                 LocalDateTime sentAt, LocalDateTime approvedAt,
                                 LocalDateTime createdAt, LocalDateTime updatedAt, BudgetComposition composition,
                                 BudgetApprovalOrigin approvalOrigin, String approvedByEmployeeId) {
        return new Budget(budgetId, serviceOrderId, budgetVersion, quotedAmount, approvedAmount,
                status, sentAt, approvedAt, createdAt, updatedAt, composition, approvalOrigin, approvedByEmployeeId);
    }

    public static Set<BudgetStatus> sentRevisionStatuses() {
        return Set.of(BudgetStatus.SENT, BudgetStatus.APPROVED, BudgetStatus.REJECTED);
    }

    public static void validateRevisionCreation(boolean approvedBudgetExists) {
        if (approvedBudgetExists) {
            throw new ApprovedBudgetExistsException();
        }
    }

    public void validateSendingRevision(Integer latestSentVersion, boolean approvedBudgetExists) {
        validateStatus(BudgetStatus.DRAFT, "enviar");
        validateRevisionCreation(approvedBudgetExists);
        if (latestSentVersion != null && budgetVersion < latestSentVersion) {
            throw new ObsoleteBudgetRevisionException();
        }
    }

    public void validateCurrentRevision(Integer latestSentVersion) {
        validateStatus(BudgetStatus.SENT, "decidir sobre");
        if (latestSentVersion == null || budgetVersion != latestSentVersion) {
            throw new ObsoleteBudgetRevisionException();
        }
    }

    public void markAsSent() {
        validateStatus(BudgetStatus.DRAFT, "enviar");
        status = BudgetStatus.SENT;
        sentAt = LocalDateTime.now();
        updatedAt = sentAt;
    }

    public void validateApprovalResend() {
        validateStatus(BudgetStatus.SENT, "reenviar a aprovação de");
    }

    public void approve() {
        validateStatus(BudgetStatus.SENT, "aprovar");
        status = BudgetStatus.APPROVED;
        approvedAmount = quotedAmount;
        approvedAt = LocalDateTime.now();
        updatedAt = approvedAt;
    }

    public void registerApproval(Integer latestSentVersion, boolean approvedBudgetExists, String employeeId) {
        if (employeeId == null || employeeId.isBlank() || employeeId.length() > 255) {
            throw new InvalidBudgetDataException("O identificador confiável do funcionário é obrigatório e deve ter até 255 caracteres");
        }
        if (status == BudgetStatus.DRAFT) {
            validateSendingRevision(latestSentVersion, approvedBudgetExists);
            markAsSent();
        } else {
            validateCurrentRevision(latestSentVersion);
        }
        approve();
        approvalOrigin = BudgetApprovalOrigin.IN_PERSON;
        approvedByEmployeeId = employeeId;
    }

    public void approveRemotely() {
        approve();
        approvalOrigin = BudgetApprovalOrigin.REMOTE;
        approvedByEmployeeId = null;
    }

    public void reject() {
        validateStatus(BudgetStatus.SENT, "rejeitar");
        status = BudgetStatus.REJECTED;
        updatedAt = LocalDateTime.now();
    }

    private void validateStatus(BudgetStatus expectedStatus, String action) {
        if (status != expectedStatus) {
            throw new InvalidBudgetStatusException(
                    "Não é possível " + action + " o orçamento no estado " + status
            );
        }
    }

    public UUID getBudgetId() { return budgetId; }
    public UUID getServiceOrderId() { return serviceOrderId; }
    public int getBudgetVersion() { return budgetVersion; }
    public BigDecimal getQuotedAmount() { return quotedAmount; }
    public BudgetComposition getComposition() { return composition; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public BudgetStatus getStatus() { return status; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public BudgetApprovalOrigin getApprovalOrigin() { return approvalOrigin; }
    public String getApprovedByEmployeeId() { return approvedByEmployeeId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
