package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BudgetApprovalEmail(String recipient, UUID budgetId, BigDecimal quotedAmount,
                                  String approvalUrl, Instant expiresAt) {
    @Override
    public String toString() { return "BudgetApprovalEmail[protected]"; }
}
