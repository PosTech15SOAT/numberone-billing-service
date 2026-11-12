package br.com.fiap.numberone.billing.budget.application.results;

import br.com.fiap.numberone.billing.budget.domain.entities.Budget;

import java.util.List;
import java.util.UUID;

public record BudgetHistory(UUID serviceOrderId, List<Budget> budgets, UUID referenceBudgetId) {

    public BudgetHistory {
        budgets = List.copyOf(budgets);
    }
}
