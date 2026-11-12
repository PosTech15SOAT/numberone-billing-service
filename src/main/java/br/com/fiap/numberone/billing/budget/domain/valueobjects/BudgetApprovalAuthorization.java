package br.com.fiap.numberone.billing.budget.domain.valueobjects;

import java.time.Instant;
import java.util.UUID;

public record BudgetApprovalAuthorization(UUID authorizationId, UUID budgetId, String tokenHash,
                                          Instant createdAt, Instant expiresAt, Instant revokedAt, Instant consumedAt) {
    public static BudgetApprovalAuthorization create(UUID authorizationId, UUID budgetId, String tokenHash,
                                                     Instant createdAt, Instant expiresAt) {
        if (authorizationId == null || budgetId == null || tokenHash == null || !tokenHash.matches("[a-f0-9]{64}")
                || createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Dados da autorização temporária inválidos");
        }
        return new BudgetApprovalAuthorization(authorizationId, budgetId, tokenHash, createdAt, expiresAt, null, null);
    }

    public static BudgetApprovalAuthorization restore(UUID authorizationId, UUID budgetId, String tokenHash,
                                                      Instant createdAt, Instant expiresAt, Instant revokedAt) {
        return restore(authorizationId, budgetId, tokenHash, createdAt, expiresAt, revokedAt, null);
    }

    public static BudgetApprovalAuthorization restore(UUID authorizationId, UUID budgetId, String tokenHash,
                                                      Instant createdAt, Instant expiresAt, Instant revokedAt, Instant consumedAt) {
        return new BudgetApprovalAuthorization(authorizationId, budgetId, tokenHash, createdAt, expiresAt, revokedAt, consumedAt);
    }

    public boolean isActiveAt(Instant now) {
        return consumedAt == null && revokedAt == null && !now.isBefore(createdAt) && now.isBefore(expiresAt);
    }
}
