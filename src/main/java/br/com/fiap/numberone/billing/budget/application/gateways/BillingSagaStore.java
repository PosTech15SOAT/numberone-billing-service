package br.com.fiap.numberone.billing.budget.application.gateways;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;

import java.util.Optional;
import java.util.UUID;

public interface BillingSagaStore {
    record Cancellation(UUID operationId, UUID serviceOrderId, UUID budgetId, Integer budgetVersion) { }
    record ClosedContext(UUID operationId, UUID serviceOrderId, UUID budgetId, Integer budgetVersion) {
        public String result() { return budgetId == null ? "NO_BUDGET" : "CANCELLED"; }
    }

    Optional<Budget> currentBudget(UUID serviceOrderId);
    Optional<UUID> orderForBudget(UUID budgetId);
    Optional<ClosedContext> previousCancellation(Cancellation request);
    ClosedContext closeContext(Cancellation request);
}
